package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.*;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Commits a delivery to a courier, and moves to the next one when that fails.
 * Doc 06 §6 steps 5–7, §7.
 *
 * <p><b>Failing over is the normal case, not the exception.</b> Doc 06 §7 answers
 * every failure with "try another provider", so this walks the usable quotes
 * cheapest-first and books the first courier that accepts. A provider that
 * refuses costs one attempt row and nothing else.
 *
 * <p><b>Every attempt is recorded, and the delivery identity never changes.</b>
 * Doc 06 §7 is explicit: a reassignment must not create a second logical
 * delivery. So {@code delivery.attempt_count} climbs and
 * {@code delivery_provider_attempt} grows, while the delivery the restaurant is
 * watching stays the one they were watching.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryBookingService {

    private final DeliveryRepository deliveries;
    private final DeliveryProviderAttemptRepository attempts;
    private final DeliveryQuotingService quoting;
    private final DeliveryProviderRegistry registry;
    private final DeliveryTimeline timeline;
    private final AuditService auditService;

    /**
     * Book the cheapest courier that will take it.
     *
     * @param excludedProviderCodes couriers already tried on this delivery
     * @return true if a courier accepted
     */
    @Transactional
    public boolean book(Delivery delivery, List<String> excludedProviderCodes, String attemptType) {
        var usable = quoting.usableQuotes(delivery.getId(), excludedProviderCodes);

        if (usable.isEmpty()) {
            return fail(delivery, DeliveryStatus.PROVIDER_UNAVAILABLE, "NO_QUOTES",
                    "No delivery partner is available for this route right now.");
        }

        for (DeliveryQuote quote : usable) {
            var adapter = registry.adapter(quote.getProviderCode());
            if (adapter == null) {
                continue;
            }

            int attemptNumber = delivery.getAttemptCount() + 1;
            var attempt = startAttempt(delivery, quote, attemptNumber, attemptType);

            try {
                var booking = adapter.book(bookingRequest(delivery, quote));

                delivery.setAttemptCount(attemptNumber);
                delivery.setDeliveryProviderId(quote.getDeliveryProviderId());
                delivery.setProviderCode(quote.getProviderCode());
                delivery.setProviderDeliveryId(booking.providerDeliveryId());
                delivery.setFee(booking.amount());
                delivery.setCurrency(booking.currency());
                delivery.setEtaMinutes(booking.etaMinutes());
                delivery.setEstimatedArrivalAt(booking.estimatedArrivalAt());
                delivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
                delivery.setBookedAt(Instant.now());
                // Cleared, because this attempt is not the failed one. A stale
                // failure left on the row would show a restaurant an error about a
                // courier who is no longer involved.
                delivery.setFailureCode(null);
                delivery.setFailureReason(null);
                deliveries.save(delivery);

                attempt.setOutcome("BOOKED");
                attempt.setProviderDeliveryId(booking.providerDeliveryId());
                attempts.save(attempt);

                // No provider name in the description. Doc 06 §10: the restaurant
                // sees their delivery, not our supply chain.
                timeline.record(delivery, DeliveryStatus.PROVIDER_SELECTED.eventName(),
                        DeliveryStatus.PROVIDER_SELECTED, "Finding a driver");

                auditService.record(null, null, "DELIVERY_BOOKED", "DELIVERY",
                        delivery.getId(), null, DeliveryStatus.PROVIDER_SELECTED.name(),
                        "Attempt %d with %s".formatted(attemptNumber, quote.getProviderCode()),
                        "SYSTEM");

                return true;

            } catch (DeliveryProviderException ex) {
                // Doc 06 §7: provider unavailable → select another. The attempt
                // stays on the record so the fallback is explicable.
                log.info("Booking with {} failed: {}", quote.getProviderCode(), ex.getMessage());
                attempt.setOutcome("FAILED");
                attempt.setFailureCode("BOOKING_FAILED");
                attempt.setFailureReason(ex.getMessage());
                attempt.setEndedAt(Instant.now());
                attempts.save(attempt);
                delivery.setAttemptCount(attemptNumber);
            }
        }

        return fail(delivery, DeliveryStatus.PROVIDER_UNAVAILABLE, "ALL_PROVIDERS_FAILED",
                "No delivery partner could take this order.");
    }

    /** Which couriers have already had a go at this consignment. */
    @Transactional(readOnly = true)
    public List<String> triedProviders(Long deliveryId) {
        var tried = new ArrayList<String>();
        attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)
                .forEach(attempt -> tried.add(attempt.getProviderCode()));
        return tried;
    }

    private DeliveryProviderAttempt startAttempt(Delivery delivery, DeliveryQuote quote,
                                                 int attemptNumber, String attemptType) {
        var attempt = new DeliveryProviderAttempt();
        attempt.setDeliveryId(delivery.getId());
        attempt.setDeliveryProviderId(quote.getDeliveryProviderId());
        attempt.setProviderCode(quote.getProviderCode());
        attempt.setAttemptNumber(attemptNumber);
        attempt.setAttemptType(attemptType);
        attempt.setOutcome("PENDING");
        attempt.setQuotedAmount(quote.getAmount());
        attempt.setStartedAt(Instant.now());
        return attempts.save(attempt);
    }

    private DeliveryProvider.BookingRequest bookingRequest(Delivery delivery, DeliveryQuote quote) {
        return new DeliveryProvider.BookingRequest(
                delivery.getSupplierOrderId(), quote.getProviderQuoteId(),
                delivery.getPickupAddress(), delivery.getPickupLatitude(),
                delivery.getPickupLongitude(), delivery.getPickupContactName(),
                delivery.getPickupContactPhone(),
                delivery.getDropAddress(), delivery.getDropLatitude(),
                delivery.getDropLongitude(), delivery.getDropContactName(),
                delivery.getDropContactPhone(),
                // Ours, so a retried booking cannot produce two couriers at one door.
                "mp-delivery-%d-%d".formatted(delivery.getId(), delivery.getAttemptCount() + 1));
    }

    private boolean fail(Delivery delivery, DeliveryStatus status, String code, String reason) {
        delivery.setStatus(status);
        delivery.setFailureCode(code);
        delivery.setFailureReason(reason);
        deliveries.save(delivery);

        timeline.record(delivery, status.eventName(), status, reason);
        return false;
    }
}
