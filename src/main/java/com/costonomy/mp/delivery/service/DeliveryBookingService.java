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
import java.util.Optional;
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
     * <p><b>Call it with no transaction open.</b> Each step commits on its own ({@code REQUIRES_NEW}); inside a caller's
     * transaction the first step would wait on that transaction's uncommitted delivery row until the lock wait timed
     * out. {@code DeliveryService} commits the delivery first and calls this afterwards.
     *
     * @param excludedProviderCodes couriers already tried on this delivery
     * @return true if a courier accepted
     */
    public boolean book(Delivery delivery, List<String> excludedProviderCodes, String attemptType) {
        final Long deliveryId = delivery.getId();
        var usable = quoting.usableQuotes(delivery.getId(), excludedProviderCodes, delivery.isRequiresColdChain());

        if (usable.isEmpty()) {
            return tx.execute(status -> fail(deliveryId, DeliveryStatus.PROVIDER_UNAVAILABLE, "NO_QUOTES",
                    "No delivery partner is available for this route right now."));
        }

        String decision = decisionSnapshot(usable, excludedProviderCodes);
        for (DeliveryQuote quote : usable) {
            var adapter = registry.adapter(quote.getProviderCode());
            if (adapter == null) {
                continue;
            }

            DeliveryProviderAttempt reserved;
            try {
                reserved = tx.execute(status -> {
                // Locked, and the delivery read afresh: the caller's copy is from before this transaction, and a
                // second booking racing this one must see our pending attempt and stand aside.
                var current = deliveries.lockById(deliveryId).orElse(delivery);
                if (current.getMode() == DeliveryMode.SUPPLIER_OWN || current.getStatus().isTerminal()
                        || current.getProviderDeliveryId() != null
                        || attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
                    // The supplier took it over, it finished, a courier already holds it (a booking that finished
                    // after the caller looked), or another booking is in progress. Every caller that wants a new
                    // courier releases the old one first, which clears this id.
                    return null;
                }
                // Numbered from the delivery as just locked, never from the caller's copy: another booking may have
                // used numbers since, and (delivery_id, attempt_number) is unique.
                int next = Math.max(current.getAttemptCount(),
                        attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(deliveryId)
                                .map(DeliveryProviderAttempt::getAttemptNumber).orElse(0)) + 1;
                var a = startAttempt(current, quote, next, attemptType);
                a.setRankPosition(usable.indexOf(quote) + 1);
                a.setSelectionReason(usable.size() == 1 ? "ONLY_OPTION" : "CHEAPEST");
                a.setDecision(decision);
                current.setAttemptCount(next);
                deliveries.save(current);
                return a;
                });
            } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
                // Lost the race for the attempt number to another booking: it won, this one stands aside.
                reserved = null;
            }
            if (reserved == null) {
                log.info("Delivery {} is no longer awaiting a courier or has a booking in progress; not booking", deliveryId);
                return false;
            }

            final DeliveryProviderAttempt attempt = reserved;
            final int attemptNumber = attempt.getAttemptNumber();
            long startedNs = System.nanoTime();
            DeliveryProvider.Booking booking = null;
            DeliveryProviderException failure = null;
            try {
                booking = adapter.book(bookingRequest(delivery, quote, attemptNumber));
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
                Boolean accepted = tx.execute(status -> {
                    var curDelivery = deliveries.lockById(delivery.getId()).orElse(delivery);
                    var curAttempt = attempts.findById(attempt.getId()).orElse(attempt);

                    // The provider call ran with no lock held, so the delivery can have moved meanwhile: cancelled, or taken
                    // over by the supplier. Recording the courier as booked would bring back a delivery that was ended and
                    // send a rider for nothing. Keep the attempt as a booking nobody wants and stand the courier down.
                    if (curDelivery.getStatus().isTerminal() || curDelivery.getMode() == DeliveryMode.SUPPLIER_OWN
                            || curDelivery.getProviderDeliveryId() != null) {
                        curAttempt.setCallMs(callMs);
                        curAttempt.setOutcome("CANCELLED");
                        curAttempt.setProviderDeliveryId(finalBooking.providerDeliveryId());
                        curAttempt.setFailureReason("The delivery no longer needed a courier when the booking came back.");
                        curAttempt.setEndedCause("DELIVERY_NO_LONGER_WANTED");
                        curAttempt.setEndedAt(Instant.now());
                        attempts.save(curAttempt);
                        return false;
                    }

                    curAttempt.setCallMs(callMs);
                    recordBooked(curDelivery, curAttempt, quote.getProviderCode(), quote.getDeliveryProviderId(),
                            finalBooking.providerDeliveryId(),
                            finalBooking.amount() != null ? finalBooking.amount() : quote.getAmount(),
                            finalBooking.currency() != null ? finalBooking.currency() : quote.getCurrency(),
                            finalBooking.etaMinutes() != null ? finalBooking.etaMinutes() : quote.getEtaMinutes(),
                            finalBooking.estimatedArrivalAt(), finalBooking.trackingUrl(), attemptNumber,
                            "Finding a driver");
                    return true;
                });
                if (!Boolean.TRUE.equals(accepted)) {
                    standDownUnwantedCourier(deliveryId, quote.getProviderCode(), finalBooking.providerDeliveryId(),
                            adapter);
                    return false;
                }
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

        return tx.execute(status -> fail(deliveryId, DeliveryStatus.PROVIDER_UNAVAILABLE, "ALL_PROVIDERS_FAILED",
                "No delivery partner could take this order."));
    }

    /**
     * Record a courier as booked: the attempt, the delivery, the assignment deadline for its vehicle, the booked event,
     * the ledger entry, the timeline row and the audit row. The one place a booking is written, so a booking recovered
     * after a lost answer is recorded exactly as a normal one is (D-204 audit, gap 11). Runs in the caller's transaction.
     */
    private void recordBooked(Delivery d, DeliveryProviderAttempt attempt, String providerCode, Long deliveryProviderId,
                              String providerDeliveryId, java.math.BigDecimal amount, String currency, Integer etaMinutes,
                              Instant estimatedArrivalAt, String trackingUrl, int attemptNumber, String timelineNote) {
        attempt.setOutcome("BOOKED");
        attempt.setProviderDeliveryId(providerDeliveryId);
        attempts.save(attempt);

        Instant now = Instant.now();
        d.setAttemptCount(Math.max(d.getAttemptCount(), attemptNumber));
        d.setDeliveryProviderId(deliveryProviderId);
        d.setProviderCode(providerCode);
        d.setProviderDeliveryId(providerDeliveryId);
        d.setFee(amount);
        d.setCurrency(currency == null ? "INR" : currency);
        d.setEtaMinutes(etaMinutes);
        d.setEstimatedArrivalAt(estimatedArrivalAt != null ? estimatedArrivalAt
                : etaMinutes == null ? null : now.plusSeconds(etaMinutes * 60L));
        if (trackingUrl != null) {
            d.setTrackingUrl(trackingUrl);
        }
        d.setStatus(DeliveryStatus.PROVIDER_SELECTED);
        d.setBookedAt(now);
        java.time.Duration deadlineTimeout = (d.getVehicleType() == VehicleType.THREE_WHEELER
                || d.getVehicleType() == VehicleType.FOUR_WHEELER_TRUCK)
                ? truckAssignmentTimeout
                : bikeAssignmentTimeout;
        d.setAssignmentDeadline(now.plus(deadlineTimeout));
        d.setFailureCode(null);
        d.setFailureReason(null);
        d.clearNoPartner();
        deliveries.save(d);

        eventPublisher.publishEvent(new com.costonomy.mp.delivery.domain.DeliveryBookedEvent(
                d.getId(), d.getAssignmentDeadline()));

        var ledgerEntry = new DeliveryLedgerEntry();
        ledgerEntry.setDeliveryId(d.getId());
        ledgerEntry.setProviderCode(providerCode);
        ledgerEntry.setProviderDeliveryId(providerDeliveryId);
        ledgerEntry.setEntryType("BOOKED");
        ledgerEntry.setAmount(amount);
        ledgerEntry.setCurrency(d.getCurrency());
        ledgerEntry.setDescription("Consignment booked with %s (Attempt %d)".formatted(providerCode, attemptNumber));
        ledger.save(ledgerEntry);

        timeline.record(d, DeliveryStatus.PROVIDER_SELECTED.eventName(), DeliveryStatus.PROVIDER_SELECTED, timelineNote);

        auditService.record(null, null, "DELIVERY_BOOKED", "DELIVERY", d.getId(), null,
                DeliveryStatus.PROVIDER_SELECTED.name(), "Attempt %d with %s".formatted(attemptNumber, providerCode),
                "SYSTEM");
    }

    private record StaleSupplierPendingInfo(
            Long deliveryId,
            Long attemptId,
            String providerCode,
            int attemptNumber,
            java.math.BigDecimal quotedAmount,
            Long deliveryProviderId,
            String ref
    ) {}

    public void recoverStalePendingAttempt(Long attemptId) {
        // Phase 1: Verify eligibility under short lock
        StaleSupplierPendingInfo info = tx.execute(status -> {
            var attemptOpt = attempts.findById(attemptId);
            if (attemptOpt.isEmpty() || !"PENDING".equals(attemptOpt.get().getOutcome())) {
                return null;
            }
            var curAttempt = attemptOpt.get();
            var delivery = deliveries.lockById(curAttempt.getDeliveryId()).orElse(null);
            if (delivery == null) return null;

            Instant now = Instant.now();
            if (!curAttempt.getStartedAt().isBefore(now.minus(pendingStaleAfter))) {
                return null;
            }

            String ref = "mp-delivery-%d-%d".formatted(delivery.getId(), curAttempt.getAttemptNumber());
            return new StaleSupplierPendingInfo(delivery.getId(), curAttempt.getId(), curAttempt.getProviderCode(),
                    curAttempt.getAttemptNumber(), curAttempt.getQuotedAmount(), curAttempt.getDeliveryProviderId(), ref);
        });

        if (info == null) {
            return;
        }

        // Phase 2: External provider lookup outside database transaction and row lock
        var adapter = registry.adapter(info.providerCode());
        Optional<DeliveryProvider.ProviderDelivery> lookedUp = Optional.empty();
        boolean lookupSupported = adapter != null && adapter.supportsLookupByReference();
        boolean lookupThrew = false;

        if (lookupSupported) {
            try {
                lookedUp = adapter.findByReference(info.ref(), info.ref());
            } catch (Exception ex) {
                lookupThrew = true;
                log.warn("Error reconciling stale attempt {} with {}: {}", info.attemptId(), info.providerCode(), ex.getMessage());
            }
        }

        final Optional<DeliveryProvider.ProviderDelivery> finalLookedUp = lookedUp;
        final boolean finalLookupSupported = lookupSupported;
        final boolean finalLookupThrew = lookupThrew;

        // Phase 3: Persist outcome under short lock
        tx.executeWithoutResult(status -> {
            var delivery = deliveries.lockById(info.deliveryId()).orElse(null);
            if (delivery == null) return;
            var curAttempt = attempts.findById(info.attemptId()).orElse(null);
            if (curAttempt == null || !"PENDING".equals(curAttempt.getOutcome())) {
                return;
            }

            Instant now = Instant.now();
            if (finalLookupSupported && !finalLookupThrew) {
                if (finalLookedUp.isPresent()) {
                    var booking = finalLookedUp.get();
                    log.info("Recovered accepted booking from {} for supplier delivery {}", curAttempt.getProviderCode(), delivery.getId());
                    // The attempt was reserved for this delivery while it wanted a courier, so adopt the booking in every
                    // state that still wants one and holds none: requested, quoted, unavailable, or waiting for a
                    // replacement after the rider cancelled or the pickup failed. Only a delivery that finished,
                    // was taken over by the supplier, or already has a courier does not want it.
                    boolean wanted = delivery.getMode() == DeliveryMode.COSTONOMY
                            && !delivery.getStatus().isTerminal()
                            && !delivery.getStatus().isInFlight()
                            && delivery.getProviderDeliveryId() == null;
                    if (wanted) {
                        recordBooked(delivery, curAttempt, curAttempt.getProviderCode(), curAttempt.getDeliveryProviderId(),
                                booking.providerDeliveryId(), curAttempt.getQuotedAmount(), "INR", booking.etaMinutes(),
                                booking.estimatedArrivalAt(), null, curAttempt.getAttemptNumber(),
                                "Booking recovered from " + curAttempt.getProviderCode());
                        return;
                    } else {
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

        // Phase 4: Stand down provider outside database transaction if recovered but not wanted
        if (finalLookupSupported && !finalLookupThrew && finalLookedUp.isPresent()) {
            var delivery = deliveries.findById(info.deliveryId()).orElse(null);
            if (delivery != null && (delivery.getProviderDeliveryId() == null || !delivery.getProviderDeliveryId().equals(finalLookedUp.get().providerDeliveryId()))) {
                try {
                    adapter.cancel(finalLookedUp.get().providerDeliveryId(), "Delivery no longer active");
                } catch (Exception ex) {
                    recoveryService.scheduleCancellation("SUPPLIER", delivery.getId(), info.providerCode(), finalLookedUp.get().providerDeliveryId(), "Delivery no longer active");
                }
            }
        }
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

    private DeliveryProvider.BookingRequest bookingRequest(Delivery delivery, DeliveryQuote quote, int attemptNumber) {
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
                // Ours, so a retried booking cannot produce two couriers at one door. Built from the attempt this call
                // reserved, so each fallback attempt has its own reference and the timeout lookup finds the one sent.
                "mp-delivery-%d-%d".formatted(delivery.getId(), attemptNumber),
                delivery.getWeightKg(), delivery.getVolumeCbm(), vehicleType,
                // Read from our own records at booking time; a carrier that needs a city,
                // pincode or declared value is told the truth or refuses, never a default.
                directory.pickupLocality(delivery.getSupplierStoreId()),
                directory.dropLocality(delivery.getOutletId()),
                directory.goodsValue(delivery.getSupplierOrderId()));
    }

    /** A courier booked for a delivery that no longer needs one. Outside any transaction; a failure is retried durably. */
    private void standDownUnwantedCourier(Long deliveryId, String providerCode, String providerDeliveryId,
                                          DeliveryProvider adapter) {
        String reason = "Delivery no longer needs a courier";
        try {
            adapter.cancel(providerDeliveryId, reason);
            log.warn("Cancelled {} courier {} booked for delivery {}, which no longer needs one",
                    providerCode, providerDeliveryId, deliveryId);
        } catch (Exception ex) {
            log.warn("Could not cancel unwanted {} courier {} for delivery {}: {}",
                    providerCode, providerDeliveryId, deliveryId, ex.getMessage());
            recoveryService.scheduleCancellation("SUPPLIER", deliveryId, providerCode, providerDeliveryId, reason);
        }
    }

    private boolean fail(Long deliveryId, DeliveryStatus status, String code, String reason) {
        var delivery = deliveries.lockById(deliveryId).orElseThrow();
        if (delivery.getStatus().isTerminal() || delivery.getMode() == DeliveryMode.SUPPLIER_OWN
                || delivery.getProviderDeliveryId() != null
                || attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
            // Ended, taken over, or given to a courier by a booking that finished while we were trying ours; or another
            // booking is still in flight and may yet succeed. Not a delivery without a partner. Our own attempts are all
            // recorded as failed by now, so a pending one is never ours.
            return false;
        }
        // The same failure again (every auto-retry that finds nobody) is not news: no new timeline row and no new
        // outbox event for it, or one delivery would announce "provider unavailable" up to fifteen times (gap 13).
        boolean news = delivery.getStatus() != status || !java.util.Objects.equals(delivery.getFailureCode(), code);
        delivery.setStatus(status);
        delivery.markNoPartner(Instant.now());
        delivery.setFailureCode(code);
        delivery.setFailureReason(reason);
        deliveries.save(delivery);

        if (news) {
            timeline.record(delivery, status.eventName(), status, reason);
        }
        return false;
    }
}
