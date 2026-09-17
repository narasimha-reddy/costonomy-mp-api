package com.costonomy.mp.delivery.provider;

import com.costonomy.mp.common.domain.Serviceability;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The delivery provider local development and CI run on. Doc 06 §11, doc 10 §6–7.
 *
 * <p>Instantiated <b>twice</b> — see {@code DeliveryConfig} — because "lowest cost
 * meeting the required ETA" (doc 06 §4) is not a rule that can be tested against a
 * single candidate. With one provider every selection strategy agrees, and a
 * selection bug is invisible. The two differ in the way real couriers do: one is
 * faster and dearer, the other cheaper and slower.
 *
 * <p>Every failure doc 06 §11 lists is reachable <b>by asking for it</b>, through
 * {@link #simulate}: a driver cancelling, a pickup failing, a provider refusing to
 * quote. A test produces a failure by ordering one, which keeps the failing path
 * and the succeeding path the same code.
 *
 * <p>It also refuses routes outside its radius, so serviceability is a real answer
 * and not a branch the tests never take.
 */
@Slf4j
public class MockDeliveryProvider implements DeliveryProvider {

    private final String code;
    private final BigDecimal baseFee;
    private final BigDecimal perKm;
    private final int minutesPerKm;
    private final double maxRadiusKm;

    /** Provider state, keyed by the id we gave out. */
    private final Map<String, MockDelivery> deliveries = new ConcurrentHashMap<>();
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    /** Providers told to misbehave on their next call, by code. */
    private final Map<String, Failure> armed = new ConcurrentHashMap<>();

    public MockDeliveryProvider(String code, BigDecimal baseFee, BigDecimal perKm,
                                int minutesPerKm, double maxRadiusKm) {
        this.code = code;
        this.baseFee = baseFee;
        this.perKm = perKm;
        this.minutesPerKm = minutesPerKm;
        this.maxRadiusKm = maxRadiusKm;
    }

    /** What a simulation asked this provider to do next. */
    public enum Failure {
        /** Refuse to quote at all — doc 06 §7's "quote failure". */
        QUOTE_FAILS,
        /** Quote, then refuse the booking — "provider unavailable". */
        BOOKING_FAILS
    }

    @Override
    public String code() {
        return code;
    }

    /**
     * Arm a failure for this provider's next call. Test and simulation only.
     *
     * <p>Not on {@link DeliveryProvider}, so nothing in the domain can reach it —
     * the same principle as the payment mock's checkout completion, and as doc 06
     * §11's requirement that simulation endpoints be protected.
     */
    public void arm(Failure failure) {
        armed.put(code, failure);
    }

    public void disarm() {
        armed.remove(code);
    }

    @Override
    public Quote quote(QuoteRequest request) {
        if (armed.get(code) == Failure.QUOTE_FAILS) {
            armed.remove(code);
            throw new DeliveryProviderException(code, "Mock quote service is down", true);
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return Quote.unserviceable("No coordinates for this route");
        }
        if (distanceKm > maxRadiusKm) {
            // An answer, not an error. Doc 06 §4 selects among providers that meet
            // serviceability, so a decline belongs in the record.
            return Quote.unserviceable("Outside the %s service area".formatted(code));
        }

        BigDecimal amount = baseFee
                .add(perKm.multiply(BigDecimal.valueOf(distanceKm)))
                .setScale(2, RoundingMode.HALF_UP);
        int eta = Math.max(10, (int) Math.ceil(distanceKm * minutesPerKm));

        var quote = new Quote("mock_q_" + UUID.randomUUID().toString().replace("-", ""),
                true, amount, "INR", eta,
                BigDecimal.valueOf(distanceKm).setScale(4, RoundingMode.HALF_UP).doubleValue(),
                Instant.now().plus(Duration.ofMinutes(15)), null);
        quotes.put(quote.providerQuoteId(), quote);
        return quote;
    }

    @Override
    public Booking book(BookingRequest request) {
        if (armed.get(code) == Failure.BOOKING_FAILS) {
            armed.remove(code);
            throw new DeliveryProviderException(code, "No riders available", true);
        }

        var quote = quotes.get(request.providerQuoteId());
        if (quote == null) {
            // A booking against a quote we never issued, or one long expired. A
            // real provider refuses this, and so must the mock, or an expired-quote
            // bug would only ever appear in production.
            throw new DeliveryProviderException(code, "Unknown or expired quote", false);
        }

        String id = "mock_d_" + UUID.randomUUID().toString().replace("-", "");
        var delivery = new MockDelivery(id, quote.amount(), quote.etaMinutes());
        // Booked, not yet assigned. The gap is real — §23A.21's "Finding the best
        // delivery partner…" state exists precisely because it is not instant.
        delivery.record(ProviderDeliveryStatus.PENDING, "Booking accepted");
        deliveries.put(id, delivery);

        return new Booking(id, quote.amount(), quote.currency(), quote.etaMinutes(),
                Instant.now().plus(Duration.ofMinutes(quote.etaMinutes())));
    }

    @Override
    public ProviderDelivery status(String providerDeliveryId) {
        var delivery = require(providerDeliveryId);
        return new ProviderDelivery(providerDeliveryId, delivery.status,
                delivery.driverName, delivery.driverPhone, delivery.driverVehicle,
                delivery.etaMinutes, delivery.estimatedArrivalAt,
                delivery.failureCode, delivery.failureReason,
                List.copyOf(delivery.events));
    }

    @Override
    public Location location(String providerDeliveryId) {
        var delivery = require(providerDeliveryId);
        // Null before a driver exists. Doc 06 §8: never fabricate a position — a
        // plausible-looking midpoint is indistinguishable from a real fix.
        return delivery.location;
    }

    @Override
    public void cancel(String providerDeliveryId, String reason) {
        var delivery = require(providerDeliveryId);
        if (delivery.status == ProviderDeliveryStatus.DELIVERED) {
            throw new DeliveryProviderException(code, "Already delivered", false);
        }
        delivery.record(ProviderDeliveryStatus.CANCELLED, reason);
    }

    // ── simulation, for tests and the protected ops endpoints ────────────

    /**
     * Drive a booked delivery forward. Doc 06 §11.
     *
     * @param status where to move it
     */
    public ProviderEvent simulate(String providerDeliveryId, ProviderDeliveryStatus status,
                                  String description) {
        var delivery = require(providerDeliveryId);

        if (status == ProviderDeliveryStatus.DRIVER_ASSIGNED) {
            delivery.driverName = "Ravi Kumar";
            delivery.driverPhone = "+919876500000";
            delivery.driverVehicle = "TS09 AB 1234";
        }
        if (status == ProviderDeliveryStatus.DRIVER_CANCELLED) {
            // The driver goes, the job remains. Doc 06 §7: reassignment must not
            // create a second delivery, so the provider keeps the same id and it
            // is Mandi that decides to go elsewhere.
            delivery.driverName = null;
            delivery.driverPhone = null;
            delivery.driverVehicle = null;
            delivery.failureCode = "DRIVER_CANCELLED";
            delivery.failureReason = description;
        }
        if (status == ProviderDeliveryStatus.PICKUP_FAILED
                || status == ProviderDeliveryStatus.DELIVERY_FAILED) {
            delivery.failureCode = status.name();
            delivery.failureReason = description;
        }

        return delivery.record(status, description);
    }

    /** Report a driver position. */
    public void simulateLocation(String providerDeliveryId, BigDecimal latitude,
                                 BigDecimal longitude, Instant recordedAt) {
        var delivery = require(providerDeliveryId);
        delivery.location = new Location(latitude, longitude, 90.0, 24.0,
                recordedAt == null ? Instant.now() : recordedAt);
    }

    /** Revise the ETA — doc 06 §9's "ETA changed". */
    public void simulateEta(String providerDeliveryId, int etaMinutes) {
        var delivery = require(providerDeliveryId);
        delivery.etaMinutes = etaMinutes;
        delivery.estimatedArrivalAt = Instant.now().plus(Duration.ofMinutes(etaMinutes));
        delivery.record(delivery.status, "ETA revised to %d minutes".formatted(etaMinutes));
    }

    private MockDelivery require(String providerDeliveryId) {
        var delivery = deliveries.get(providerDeliveryId);
        if (delivery == null) {
            throw new DeliveryProviderException(code,
                    "Unknown mock delivery " + providerDeliveryId, false);
        }
        return delivery;
    }

    /** Mutable provider-side state. Never leaves this class. */
    private final class MockDelivery {
        private final String id;
        private final BigDecimal amount;
        private ProviderDeliveryStatus status = ProviderDeliveryStatus.PENDING;
        private String driverName;
        private String driverPhone;
        private String driverVehicle;
        private Integer etaMinutes;
        private Instant estimatedArrivalAt;
        private String failureCode;
        private String failureReason;
        private Location location;
        private final List<ProviderEvent> events = new ArrayList<>();

        private MockDelivery(String id, BigDecimal amount, Integer etaMinutes) {
            this.id = id;
            this.amount = amount;
            this.etaMinutes = etaMinutes;
            this.estimatedArrivalAt = etaMinutes == null ? null
                    : Instant.now().plus(Duration.ofMinutes(etaMinutes));
        }

        private ProviderEvent record(ProviderDeliveryStatus status, String description) {
            this.status = status;
            var event = new ProviderEvent(
                    "mock_e_" + UUID.randomUUID().toString().replace("-", ""),
                    status, description, Instant.now());
            events.add(0, event);
            return event;
        }
    }
}
