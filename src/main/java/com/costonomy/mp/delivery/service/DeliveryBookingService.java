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
    private final DeliveryLedgerRepository ledger;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final DeliveryDirectory directory;

    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.delivery.bike-assignment-timeout:PT3M}")
    private java.time.Duration bikeAssignmentTimeout = java.time.Duration.ofMinutes(3);

    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.delivery.truck-assignment-timeout:PT12M}")
    private java.time.Duration truckAssignmentTimeout = java.time.Duration.ofMinutes(12);

    /**
     * Book the cheapest courier that will take it.
     *
     * @param excludedProviderCodes couriers already tried on this delivery
     * @return true if a courier accepted
     */
    @Transactional
    public boolean book(Delivery delivery, List<String> excludedProviderCodes, String attemptType) {
        var usable = quoting.usableQuotes(delivery.getId(), excludedProviderCodes, delivery.isRequiresColdChain());

        if (usable.isEmpty()) {
            return fail(delivery, DeliveryStatus.PROVIDER_UNAVAILABLE, "NO_QUOTES",
                    "No delivery partner is available for this route right now.");
        }

        String decision = decisionSnapshot(usable, excludedProviderCodes);
        for (DeliveryQuote quote : usable) {
            var adapter = registry.adapter(quote.getProviderCode());
            if (adapter == null) {
                continue;
            }

            int attemptNumber = delivery.getAttemptCount() + 1;
            var attempt = startAttempt(delivery, quote, attemptNumber, attemptType);
            // What the choice looked like when it was made (D-197 addendum). Supplier delivery still orders by price
            // alone, so the reason is always the price order; the reliability fields are for food, which ranks on them.
            attempt.setRankPosition(usable.indexOf(quote) + 1);
            attempt.setSelectionReason(usable.size() == 1 ? "ONLY_OPTION" : "CHEAPEST");
            attempt.setDecision(decision);

            long startedNs = System.nanoTime();
            try {
                var booking = adapter.book(bookingRequest(delivery, quote));
                attempt.setCallMs(elapsedMs(startedNs));

                delivery.setAttemptCount(attemptNumber);
                delivery.setDeliveryProviderId(quote.getDeliveryProviderId());
                delivery.setProviderCode(quote.getProviderCode());
                delivery.setProviderDeliveryId(booking.providerDeliveryId());
                // A carrier that does not restate the fare or arrival time on booking (Pidge) is booked at the quote's.
                var bookedAmount = booking.amount() != null ? booking.amount() : quote.getAmount();
                var bookedCurrency = booking.currency() != null ? booking.currency() : quote.getCurrency();
                var bookedEta = booking.etaMinutes() != null ? booking.etaMinutes() : quote.getEtaMinutes();
                var bookedArrival = booking.estimatedArrivalAt() != null ? booking.estimatedArrivalAt()
                        : bookedEta == null ? null : Instant.now().plusSeconds(bookedEta * 60L);
                delivery.setFee(bookedAmount);
                delivery.setCurrency(bookedCurrency);
                delivery.setEtaMinutes(bookedEta);
                delivery.setEstimatedArrivalAt(bookedArrival);
                if (booking.trackingUrl() != null) {
                    delivery.setTrackingUrl(booking.trackingUrl());
                }
                delivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
                delivery.setBookedAt(Instant.now());
                java.time.Duration deadlineTimeout = (delivery.getVehicleType() == VehicleType.THREE_WHEELER
                        || delivery.getVehicleType() == VehicleType.FOUR_WHEELER_TRUCK)
                        ? truckAssignmentTimeout
                        : bikeAssignmentTimeout;
                delivery.setAssignmentDeadline(Instant.now().plus(deadlineTimeout));
                // Cleared, because this attempt is not the failed one. A stale
                // failure left on the row would show a restaurant an error about a
                // courier who is no longer involved.
                delivery.setFailureCode(null);
                delivery.setFailureReason(null);
                delivery.clearNoPartner();
                deliveries.save(delivery);

                eventPublisher.publishEvent(new com.costonomy.mp.delivery.domain.DeliveryBookedEvent(
                        delivery.getId(), delivery.getAssignmentDeadline()));

                attempt.setOutcome("BOOKED");
                attempt.setProviderDeliveryId(booking.providerDeliveryId());
                attempts.save(attempt);

                // Record into central delivery financial ledger
                var ledgerEntry = new DeliveryLedgerEntry();
                ledgerEntry.setDeliveryId(delivery.getId());
                ledgerEntry.setProviderCode(quote.getProviderCode());
                ledgerEntry.setProviderDeliveryId(booking.providerDeliveryId());
                ledgerEntry.setEntryType("BOOKED");
                ledgerEntry.setAmount(bookedAmount);
                ledgerEntry.setCurrency(bookedCurrency);
                ledgerEntry.setDescription("Consignment booked with %s (Attempt %d)".formatted(quote.getProviderCode(), attemptNumber));
                ledger.save(ledgerEntry);

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
                attempt.setCallMs(elapsedMs(startedNs));
                attempt.setRetryable(ex.retryable());
                attempt.setEndedCause("TIMEOUT".equals(ex.errorClass()) ? "PROVIDER_TIMEOUT" : "PROVIDER_REFUSED");
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

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static int elapsedMs(long startedNs) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNs) / 1_000_000L);
    }

    /** Every courier considered for this booking, in the order they will be asked, and those already ruled out. */
    private static String decisionSnapshot(List<DeliveryQuote> usable, List<String> excluded) {
        try {
            var candidates = new ArrayList<java.util.Map<String, Object>>();
            for (int i = 0; i < usable.size(); i++) {
                var q = usable.get(i);
                var row = new java.util.LinkedHashMap<String, Object>();
                row.put("provider", q.getProviderCode());
                row.put("rank", i + 1);
                row.put("amount", q.getAmount());
                row.put("etaMinutes", q.getEtaMinutes());
                candidates.add(row);
            }
            var snapshot = new java.util.LinkedHashMap<String, Object>();
            snapshot.put("candidates", candidates);
            snapshot.put("alreadyTried", excluded);
            return JSON.writeValueAsString(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // The snapshot is a record for later, never a reason to fail a booking.
            return null;
        }
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
        var vehicleType = quote.getVehicleType() != null ? quote.getVehicleType()
                : delivery.getVehicleType() != null ? delivery.getVehicleType()
                : VehicleType.fromWeight(delivery.getWeightKg());

        return new DeliveryProvider.BookingRequest(
                delivery.getSupplierOrderId(), quote.getProviderQuoteId(),
                delivery.getPickupAddress(), delivery.getPickupLatitude(),
                delivery.getPickupLongitude(), delivery.getPickupContactName(),
                delivery.getPickupContactPhone(),
                delivery.getDropAddress(), delivery.getDropLatitude(),
                delivery.getDropLongitude(), delivery.getDropContactName(),
                delivery.getDropContactPhone(),
                // Ours, so a retried booking cannot produce two couriers at one door.
                "mp-delivery-%d-%d".formatted(delivery.getId(), delivery.getAttemptCount() + 1),
                delivery.getWeightKg(), delivery.getVolumeCbm(), vehicleType,
                // Read from our own records at booking time; a carrier that needs a city,
                // pincode or declared value is told the truth or refuses, never a default.
                directory.pickupLocality(delivery.getSupplierStoreId()),
                directory.dropLocality(delivery.getOutletId()),
                directory.goodsValue(delivery.getSupplierOrderId()));
    }

    private boolean fail(Delivery delivery, DeliveryStatus status, String code, String reason) {
        delivery.setStatus(status);
        delivery.markNoPartner(Instant.now());
        delivery.setFailureCode(code);
        delivery.setFailureReason(reason);
        deliveries.save(delivery);

        timeline.record(delivery, status.eventName(), status, reason);
        return false;
    }
}
