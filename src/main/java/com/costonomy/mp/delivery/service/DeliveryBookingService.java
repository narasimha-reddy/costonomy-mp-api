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
    private final DeliveryRecoveryService recoveryService;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public DeliveryBookingService(DeliveryRepository deliveries,
                                  DeliveryProviderAttemptRepository attempts,
                                  DeliveryQuotingService quoting,
                                  DeliveryProviderRegistry registry,
                                  DeliveryTimeline timeline,
                                  AuditService auditService,
                                  DeliveryLedgerRepository ledger,
                                  org.springframework.context.ApplicationEventPublisher eventPublisher,
                                  DeliveryDirectory directory,
                                  DeliveryRecoveryService recoveryService,
                                  org.springframework.transaction.PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.quoting = quoting;
        this.registry = registry;
        this.timeline = timeline;
        this.auditService = auditService;
        this.ledger = ledger;
        this.eventPublisher = eventPublisher;
        this.directory = directory;
        this.recoveryService = recoveryService;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.delivery.bike-assignment-timeout:PT3M}")
    private java.time.Duration bikeAssignmentTimeout = java.time.Duration.ofMinutes(3);

    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.delivery.truck-assignment-timeout:PT12M}")
    private java.time.Duration truckAssignmentTimeout = java.time.Duration.ofMinutes(12);

    @org.springframework.beans.factory.annotation.Value("${costonomy.mp.delivery.pending-stale-after:PT2M}")
    private java.time.Duration pendingStaleAfter = java.time.Duration.ofMinutes(2);

    /**
     * Book the cheapest courier that will take it.
     * Keeps remote provider calls outside long-running database transactions.
     *
     * @param excludedProviderCodes couriers already tried on this delivery
     * @return true if a courier accepted
     */
    public boolean book(Delivery delivery, List<String> excludedProviderCodes, String attemptType) {
        var usable = quoting.usableQuotes(delivery.getId(), excludedProviderCodes, delivery.isRequiresColdChain());

        if (usable.isEmpty()) {
            return tx.execute(status -> fail(delivery, DeliveryStatus.PROVIDER_UNAVAILABLE, "NO_QUOTES",
                    "No delivery partner is available for this route right now."));
        }

        String decision = decisionSnapshot(usable, excludedProviderCodes);
        for (DeliveryQuote quote : usable) {
            var adapter = registry.adapter(quote.getProviderCode());
            if (adapter == null) {
                continue;
            }

            int attemptNumber = delivery.getAttemptCount() + 1;
            var attempt = tx.execute(status -> {
                var a = startAttempt(delivery, quote, attemptNumber, attemptType);
                a.setRankPosition(usable.indexOf(quote) + 1);
                a.setSelectionReason(usable.size() == 1 ? "ONLY_OPTION" : "CHEAPEST");
                a.setDecision(decision);
                delivery.setAttemptCount(attemptNumber);
                deliveries.save(delivery);
                return a;
            });

            long startedNs = System.nanoTime();
            DeliveryProvider.Booking booking = null;
            DeliveryProviderException failure = null;
            try {
                booking = adapter.book(bookingRequest(delivery, quote));
            } catch (DeliveryProviderException ex) {
                failure = ex;
            } catch (Exception ex) {
                failure = new DeliveryProviderException(quote.getProviderCode(), ex.getMessage(), true);
            }
            int callMs = elapsedMs(startedNs);

            boolean ambiguous = false;
            if (failure != null && ("TIMEOUT".equals(failure.errorClass()) || failure.retryable())) {
                if (adapter.supportsLookupByReference()) {
                    try {
                        String ref = "mp-delivery-%d-%d".formatted(delivery.getId(), attemptNumber);
                        var lookedUp = adapter.findByReference(ref, ref);
                        if (lookedUp.isPresent()) {
                            var d = lookedUp.get();
                            booking = new DeliveryProvider.Booking(d.providerDeliveryId(), quote != null ? quote.getAmount() : null, "INR",
                                    d.etaMinutes(), d.estimatedArrivalAt(), null);
                            failure = null;
                        }
                    } catch (Exception lookupEx) {
                        log.warn("Reference lookup failed for {}: {}", quote.getProviderCode(), lookupEx.getMessage());
                        ambiguous = true;
                    }
                } else {
                    ambiguous = true;
                }
            }

            if (ambiguous) {
                final DeliveryProviderException finalFailure = failure;
                tx.executeWithoutResult(status -> {
                    var curAttempt = attempts.findById(attempt.getId()).orElse(attempt);
                    curAttempt.setCallMs(callMs);
                    curAttempt.setOutcome("FAILED");
                    curAttempt.setFailureCode("AMBIGUOUS_PROVIDER_OUTCOME");
                    curAttempt.setFailureReason(finalFailure != null ? finalFailure.getMessage() : "Ambiguous provider outcome");
                    curAttempt.setEndedCause("AMBIGUOUS_PROVIDER_OUTCOME");
                    curAttempt.setEndedAt(Instant.now());
                    attempts.save(curAttempt);

                    var curDelivery = deliveries.lockById(delivery.getId()).orElse(delivery);
                    curDelivery.setReconciliationRequired(true);
                    curDelivery.setReconciliationReason("Booking outcome ambiguous with " + quote.getProviderCode() + "; operator review required.");
                    curDelivery.setFailureCode("AMBIGUOUS_PROVIDER_OUTCOME");
                    curDelivery.setFailureReason("Booking outcome ambiguous with " + quote.getProviderCode() + "; operator review required.");
                    deliveries.save(curDelivery);
                    timeline.record(curDelivery, "ReconciliationRequired", curDelivery.getStatus(),
                            "Ambiguous outcome with " + quote.getProviderCode() + "; manual reconciliation required.");
                });
                return false;
            }

            if (booking != null) {
                final DeliveryProvider.Booking finalBooking = booking;
                tx.executeWithoutResult(status -> {
                    var curDelivery = deliveries.lockById(delivery.getId()).orElse(delivery);
                    var curAttempt = attempts.findById(attempt.getId()).orElse(attempt);

                    curAttempt.setCallMs(callMs);
                    curAttempt.setOutcome("BOOKED");
                    curAttempt.setProviderDeliveryId(finalBooking.providerDeliveryId());
                    attempts.save(curAttempt);

                    curDelivery.setAttemptCount(attemptNumber);
                    curDelivery.setDeliveryProviderId(quote.getDeliveryProviderId());
                    curDelivery.setProviderCode(quote.getProviderCode());
                    curDelivery.setProviderDeliveryId(finalBooking.providerDeliveryId());
                    var bookedAmount = finalBooking.amount() != null ? finalBooking.amount() : quote.getAmount();
                    var bookedCurrency = finalBooking.currency() != null ? finalBooking.currency() : quote.getCurrency();
                    var bookedEta = finalBooking.etaMinutes() != null ? finalBooking.etaMinutes() : quote.getEtaMinutes();
                    var bookedArrival = finalBooking.estimatedArrivalAt() != null ? finalBooking.estimatedArrivalAt()
                            : bookedEta == null ? null : Instant.now().plusSeconds(bookedEta * 60L);
                    curDelivery.setFee(bookedAmount);
                    curDelivery.setCurrency(bookedCurrency);
                    curDelivery.setEtaMinutes(bookedEta);
                    curDelivery.setEstimatedArrivalAt(bookedArrival);
                    if (finalBooking.trackingUrl() != null) {
                        curDelivery.setTrackingUrl(finalBooking.trackingUrl());
                    }
                    curDelivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
                    curDelivery.setBookedAt(Instant.now());
                    java.time.Duration deadlineTimeout = (curDelivery.getVehicleType() == VehicleType.THREE_WHEELER
                            || curDelivery.getVehicleType() == VehicleType.FOUR_WHEELER_TRUCK)
                            ? truckAssignmentTimeout
                            : bikeAssignmentTimeout;
                    curDelivery.setAssignmentDeadline(Instant.now().plus(deadlineTimeout));
                    curDelivery.setFailureCode(null);
                    curDelivery.setFailureReason(null);
                    curDelivery.clearNoPartner();
                    deliveries.save(curDelivery);

                    eventPublisher.publishEvent(new com.costonomy.mp.delivery.domain.DeliveryBookedEvent(
                            curDelivery.getId(), curDelivery.getAssignmentDeadline()));

                    var ledgerEntry = new DeliveryLedgerEntry();
                    ledgerEntry.setDeliveryId(curDelivery.getId());
                    ledgerEntry.setProviderCode(quote.getProviderCode());
                    ledgerEntry.setProviderDeliveryId(finalBooking.providerDeliveryId());
                    ledgerEntry.setEntryType("BOOKED");
                    ledgerEntry.setAmount(bookedAmount);
                    ledgerEntry.setCurrency(bookedCurrency);
                    ledgerEntry.setDescription("Consignment booked with %s (Attempt %d)".formatted(quote.getProviderCode(), attemptNumber));
                    ledger.save(ledgerEntry);

                    timeline.record(curDelivery, DeliveryStatus.PROVIDER_SELECTED.eventName(),
                            DeliveryStatus.PROVIDER_SELECTED, "Finding a driver");

                    auditService.record(null, null, "DELIVERY_BOOKED", "DELIVERY",
                            curDelivery.getId(), null, DeliveryStatus.PROVIDER_SELECTED.name(),
                            "Attempt %d with %s".formatted(attemptNumber, quote.getProviderCode()),
                            "SYSTEM");
                });
                return true;
            }

            final DeliveryProviderException finalFailure = failure;
            tx.executeWithoutResult(status -> {
                log.info("Booking with {} failed: {}", quote.getProviderCode(), finalFailure.getMessage());
                var curAttempt = attempts.findById(attempt.getId()).orElse(attempt);
                curAttempt.setOutcome("FAILED");
                curAttempt.setFailureCode("BOOKING_FAILED");
                curAttempt.setFailureReason(finalFailure.getMessage());
                curAttempt.setCallMs(callMs);
                curAttempt.setRetryable(finalFailure.retryable());
                curAttempt.setEndedCause("TIMEOUT".equals(finalFailure.errorClass()) ? "PROVIDER_TIMEOUT" : "PROVIDER_REFUSED");
                curAttempt.setEndedAt(Instant.now());
                attempts.save(curAttempt);
            });
        }

        return tx.execute(status -> fail(delivery, DeliveryStatus.PROVIDER_UNAVAILABLE, "ALL_PROVIDERS_FAILED",
                "No delivery partner could take this order."));
    }

    public void recoverStalePendingAttempt(Long attemptId) {
        var attemptOpt = attempts.findById(attemptId);
        if (attemptOpt.isEmpty() || !"PENDING".equals(attemptOpt.get().getOutcome())) {
            return;
        }
        var attempt = attemptOpt.get();
        Long deliveryId = attempt.getDeliveryId();

        tx.executeWithoutResult(status -> {
            var delivery = deliveries.lockById(deliveryId).orElse(null);
            if (delivery == null) return;
            var curAttempt = attempts.findById(attemptId).orElse(null);
            if (curAttempt == null || !"PENDING".equals(curAttempt.getOutcome())) {
                return;
            }

            Instant now = Instant.now();
            if (!curAttempt.getStartedAt().isBefore(now.minus(pendingStaleAfter))) {
                return;
            }

            var adapter = registry.adapter(curAttempt.getProviderCode());
            String ref = "mp-delivery-%d-%d".formatted(delivery.getId(), curAttempt.getAttemptNumber());

            if (adapter != null && adapter.supportsLookupByReference()) {
                try {
                    var lookedUp = adapter.findByReference(ref, ref);
                    if (lookedUp.isPresent()) {
                        var booking = lookedUp.get();
                        log.info("Recovered accepted booking from {} for supplier delivery {}", curAttempt.getProviderCode(), delivery.getId());
                        if (delivery.getStatus() == DeliveryStatus.DELIVERY_REQUESTED || delivery.getStatus() == DeliveryStatus.PROVIDER_UNAVAILABLE) {
                            curAttempt.setOutcome("BOOKED");
                            curAttempt.setProviderDeliveryId(booking.providerDeliveryId());
                            curAttempt.setEndedAt(now);
                            attempts.save(curAttempt);

                            delivery.setDeliveryProviderId(curAttempt.getDeliveryProviderId());
                            delivery.setProviderCode(curAttempt.getProviderCode());
                            delivery.setProviderDeliveryId(booking.providerDeliveryId());
                            var bookedAmount = curAttempt.getQuotedAmount();
                            delivery.setFee(bookedAmount);
                            delivery.setCurrency("INR");
                            delivery.setEtaMinutes(booking.etaMinutes());
                            delivery.setEstimatedArrivalAt(booking.estimatedArrivalAt() != null ? booking.estimatedArrivalAt()
                                    : booking.etaMinutes() == null ? null : now.plusSeconds(booking.etaMinutes() * 60L));
                            delivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
                            delivery.setBookedAt(now);
                            delivery.setAssignmentDeadline(now.plus(bikeAssignmentTimeout));
                            delivery.setFailureCode(null);
                            delivery.setFailureReason(null);
                            delivery.clearNoPartner();
                            deliveries.save(delivery);

                            timeline.record(delivery, DeliveryStatus.PROVIDER_SELECTED.eventName(),
                                    DeliveryStatus.PROVIDER_SELECTED, "Booking recovered from " + curAttempt.getProviderCode());
                            return;
                        } else {
                            try {
                                adapter.cancel(booking.providerDeliveryId(), "Delivery no longer active");
                            } catch (Exception ex) {
                                recoveryService.scheduleCancellation("SUPPLIER", delivery.getId(), curAttempt.getProviderCode(), booking.providerDeliveryId(), "Delivery no longer active");
                            }
                            curAttempt.setOutcome("CANCELLED");
                            curAttempt.setEndedCause("RECOVERED_BUT_NOT_WANTED");
                            curAttempt.setEndedAt(now);
                            attempts.save(curAttempt);
                            return;
                        }
                    } else {
                        curAttempt.setOutcome("FAILED");
                        curAttempt.setFailureCode("ABANDONED");
                        curAttempt.setEndedCause("ABANDONED_STALE_PENDING");
                        curAttempt.setEndedAt(now);
                        attempts.save(curAttempt);
                        timeline.record(delivery, "StaleAttemptAbandoned", delivery.getStatus(), "Stale booking attempt abandoned as provider confirmed not booked");
                        return;
                    }
                } catch (Exception ex) {
                    log.warn("Error reconciling stale attempt {} with {}: {}", curAttempt.getId(), curAttempt.getProviderCode(), ex.getMessage());
                }
            }

            curAttempt.setOutcome("FAILED");
            curAttempt.setFailureCode("AMBIGUOUS_STALE_PENDING");
            curAttempt.setEndedCause("AMBIGUOUS_STALE_PENDING");
            curAttempt.setEndedAt(now);
            attempts.save(curAttempt);

            delivery.setReconciliationRequired(true);
            delivery.setReconciliationReason("Stale attempt %d with %s could not be reconciled".formatted(curAttempt.getAttemptNumber(), curAttempt.getProviderCode()));
            deliveries.save(delivery);
            timeline.record(delivery, "ReconciliationRequired", delivery.getStatus(), "Stale attempt could not be reconciled with " + curAttempt.getProviderCode());
        });
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
