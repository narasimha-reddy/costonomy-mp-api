package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.*;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxStages;
import com.costonomy.mp.delivery.repository.*;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Delivery, from ready-for-pickup to delivered. Doc 06 §6, doc 04 §14.
 *
 * <p>Two modes, and the difference is who reports movement. On <b>Costonomy
 * delivery</b> Mandi quotes, books and follows a courier's events. On <b>supplier
 * own delivery</b> the supplier is the courier: there is no provider, no quoting
 * and no tracking, and they report their own progress — doc 06 §2 says as much,
 * and inventing a position for a van we cannot see would be exactly the
 * fabrication doc 06 §8 forbids.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final DeliveryLocationRepository locations;
    private final DeliveryProviderAttemptRepository attempts;
    private final DeliveryQuotingService quoting;
    private final DeliveryBookingService booking;
    private final DeliveryEventService eventService;
    private final DeliveryOrderBridge orderBridge;
    private final DeliveryTimeline timeline;
    private final DeliveryDirectory directory;
    private final DeliveryProviderRegistry registry;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final PidgeProperties pidgeProperties;
    private final DeliveryRecoveryService recoveryService;
    private final OrderCancellationPort orders;
    private final SupplierCourierPayableSync payableSync;
    private final SupplierDeliveryMovementService movementService;
    private final PlatformTransactionManager txManager;

    record CancelPlan(Long deliveryId, String providerCode, String providerDeliveryId, String reason) {}

    private record Prepared(
            Long deliveryId,
            String orderNumber,
            List<String> toBook,
            String attemptType,
            boolean needsQuoting,
            BigDecimal deliveryFee,
            Integer requiredEtaMinutes,
            CancelPlan cancelPlan) {

        static Prepared nothingToBook(Long deliveryId, String orderNumber) {
            return new Prepared(deliveryId, orderNumber, null, null, false, null, null, null);
        }

        static Prepared readyToBook(Long deliveryId, String orderNumber, List<String> toBook,
                                    String attemptType, CancelPlan cancelPlan) {
            return new Prepared(deliveryId, orderNumber, toBook, attemptType, false, null, null, cancelPlan);
        }

        static Prepared needsQuotes(Long deliveryId, String orderNumber, BigDecimal deliveryFee,
                                    Integer requiredEtaMinutes, List<String> excluded,
                                    String attemptType, CancelPlan cancelPlan) {
            return new Prepared(deliveryId, orderNumber, excluded, attemptType, true, deliveryFee, requiredEtaMinutes, cancelPlan);
        }
    }

    private record SwitchToOwnResult(CancelPlan cancelPlan, String orderNumber, boolean alreadyOwn) {}

    private <T> T inTx(java.util.function.Supplier<T> work) {
        // REQUIRES_NEW: also correct when called from an AFTER_COMMIT listener, where the committed transaction is
        // still bound to the thread and joining it would silently never commit.
        var template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    /**
     * Provider cancellation step, run with no transaction open. If provider cancellation fails or throws,
     * a durable recovery task is scheduled to retry it.
     */
    void cancelAtProvider(CancelPlan plan) {
        if (plan == null || plan.providerDeliveryId() == null) {
            return;
        }
        var adapter = registry.adapter(plan.providerCode());
        if (adapter != null) {
            try {
                adapter.cancel(plan.providerDeliveryId(), plan.reason());
            } catch (DeliveryProviderException ex) {
                log.warn("Could not cancel {} delivery {}: {}",
                        plan.providerCode(), plan.providerDeliveryId(),
                        ex.getMessage());
                recoveryService.scheduleCancellation("SUPPLIER", plan.deliveryId(),
                        plan.providerCode(), plan.providerDeliveryId(), plan.reason());
            } catch (Exception ex) {
                log.warn("Unexpected error cancelling {} delivery {}: {}",
                        plan.providerCode(), plan.providerDeliveryId(),
                        ex.getMessage());
                recoveryService.scheduleCancellation("SUPPLIER", plan.deliveryId(),
                        plan.providerCode(), plan.providerDeliveryId(), plan.reason());
            }
        }
    }

    /**
     * Gather provider quotes outside any database transaction or row lock.
     */
    private DeliveryQuotingService.Outcome gatherQuotesOutsideTx(
            Long deliveryId, BigDecimal deliveryFee, Integer requiredEtaMinutes, List<String> excluded) {
        var delivery = inTx(() -> deliveries.findById(deliveryId).orElse(null));
        if (delivery == null) {
            return null;
        }
        BigDecimal fee = deliveryFee;
        if (fee == null && delivery.getSupplierOrderId() != null) {
            var order = directory.order(delivery.getSupplierOrderId());
            if (order != null) {
                fee = order.deliveryFee();
            }
        }
        return quoting.gather(delivery, fee,
                directory.consignmentWeightGrams(delivery.getSupplierOrderId()),
                requiredEtaMinutes, excluded);
    }

    /**
     * Whether a courier may still be quoted and booked for this delivery: Costonomy's to arrange, not finished, not
     * on a vehicle, and with no courier holding it. Quotes are gathered with no lock held, so by the time the result
     * is written the supplier may have taken the delivery over ({@link #switchToOwn}), a manual reassign or a webhook
     * may have moved it, or another booking may have won; the result is then stale and must change nothing.
     */
    private static boolean awaitingCourier(Delivery delivery) {
        return delivery.getMode() == DeliveryMode.COSTONOMY
                && !delivery.getStatus().isTerminal()
                && !delivery.getStatus().isInFlight()
                && delivery.getProviderDeliveryId() == null;
    }

    /**
     * Apply the quote gathering outcome under a short transaction. Rechecks under the lock that the delivery is still
     * awaiting a courier; if not, writes nothing and returns false.
     */
    private boolean applyQuoteOutcome(Long deliveryId, DeliveryQuotingService.Outcome outcome) {
        return Boolean.TRUE.equals(inTx(() -> {
            var delivery = deliveries.lockById(deliveryId).orElse(null);
            if (delivery == null || !awaitingCourier(delivery)) {
                return false;
            }
            if (outcome == null || !outcome.anyServiceable()) {
                boolean alreadyQuoteFailed = delivery.getStatus() == DeliveryStatus.QUOTE_FAILED;
                delivery.setStatus(DeliveryStatus.QUOTE_FAILED);
                delivery.markNoPartner(Instant.now());
                String previousCode = delivery.getFailureCode();
                if (delivery.isRequiresColdChain()) {
                    delivery.setFailureCode("NO_COLD_CHAIN_CARRIER");
                    delivery.setFailureReason("No delivery partner with verified temperature-controlled transport "
                            + "covers this route.");
                } else {
                    delivery.setFailureCode("NO_SERVICEABLE_PROVIDER");
                    delivery.setFailureReason("No delivery partner covers this route right now.");
                }
                deliveries.save(delivery);
                // A repeat of the same failure on each retry is not announced again (gap 13).
                if (!alreadyQuoteFailed || !java.util.Objects.equals(previousCode, delivery.getFailureCode())) {
                    timeline.record(delivery, DeliveryStatus.QUOTE_FAILED.eventName(),
                            DeliveryStatus.QUOTE_FAILED,
                            delivery.getFailureReason());
                }
                return false;
            }

            delivery.setStatus(DeliveryStatus.QUOTE_RECEIVED);
            deliveries.save(delivery);
            return true;
        }));
    }

    /**
     * Execute cancellations, quoting, and bookings outside database transactions.
     */
    private void executePrepared(Prepared p) {
        if (p == null) {
            return;
        }
        if (p.cancelPlan() != null) {
            cancelAtProvider(p.cancelPlan());
        }
        List<String> toBook = p.toBook();
        if (p.needsQuoting()) {
            var outcome = gatherQuotesOutsideTx(p.deliveryId(), p.deliveryFee(), p.requiredEtaMinutes(), p.toBook());
            boolean serviceable = applyQuoteOutcome(p.deliveryId(), outcome);
            if (!serviceable) {
                toBook = null;
            }
        }
        if (toBook != null) {
            var delivery = inTx(() -> deliveries.findById(p.deliveryId()).orElse(null));
            // book() checks again under the lock before it reserves an attempt.
            if (delivery != null && awaitingCourier(delivery)) {
                booking.book(delivery, toBook, p.attemptType());
            }
        }
    }

    private DeliveryDtos.DeliveryResponse quoteBookAndRespond(Prepared p) {
        if (p == null) {
            return null;
        }
        executePrepared(p);
        return inTx(() -> toResponse(deliveries.findById(p.deliveryId()).orElseThrow(), p.orderNumber()));
    }

    private DeliveryDtos.DeliveryResponse bookAndRespond(Prepared p) {
        return quoteBookAndRespond(p);
    }

    /** Doc 06 §8: past this, a position is shown as stale rather than as current. */
    @Value("${costonomy.mp.delivery.auto-retry.window:PT30M}")
    private Duration retryWindow = Duration.ofMinutes(30);

    @Value("${costonomy.mp.delivery.location-stale-after:60s}")
    private Duration locationStaleAfter;

    // ── Requesting ───────────────────────────────────────────────────────

    /**
     * Arrange delivery for an order that is ready. Doc 06 §6.
     *
     * <p>Idempotent on the order: {@code uk_delivery_order} means a retried request
     * returns the delivery that already exists rather than sending a second courier
     * to the same door.
     */
    public DeliveryDtos.DeliveryResponse request(Long actorId, Long supplierOrderId,
                                                 DeliveryDtos.RequestDeliveryRequest body) {
        return bookAndRespond(inTx(() -> prepareRequest(actorId, supplierOrderId, body)));
    }

    private Prepared prepareRequest(Long actorId, Long supplierOrderId,
                                    DeliveryDtos.RequestDeliveryRequest body) {

        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        // Either side may arrange it — the supplier when the goods are packed, the
        // restaurant when they are waiting — so the check accepts either scope.
        requireEitherSide(actorId, order.outletId(), order.supplierStoreId(), supplierOrderId);

        var existing = deliveries.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (existing != null) {
            return Prepared.nothingToBook(existing.getId(), order.orderNumber());
        }

        if (!"READY_FOR_PICKUP".equals(order.status())) {
            // Doc 06 §6 step 1. Dispatching a courier to goods that are not packed
            // wastes their time and starts an ETA the supplier cannot meet.
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order isn't ready for pickup yet.");
        }

        var pickup = directory.pickupFor(order.supplierStoreId());
        var drop = directory.dropFor(order.outletId());
        if (pickup == null || drop == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This order is missing a pickup or delivery address.");
        }

        var policy = directory.deliveryPolicy(order.supplierStoreId());
        var mode = resolveMode(body.mode(), order.deliveryMode(), policy);

        var delivery = new Delivery();
        delivery.setSupplierOrderId(supplierOrderId);
        delivery.setOutletId(order.outletId());
        delivery.setSupplierStoreId(order.supplierStoreId());
        delivery.setMode(mode);
        delivery.setPickupAddress(pickup.address());
        delivery.setPickupLatitude(pickup.latitude());
        delivery.setPickupLongitude(pickup.longitude());
        delivery.setPickupContactName(pickup.contactName());
        delivery.setPickupContactPhone(pickup.contactPhone());
        delivery.setDropAddress(drop.address());
        delivery.setDropLatitude(drop.latitude());
        delivery.setDropLongitude(drop.longitude());
        delivery.setDropContactName(drop.contactName());
        delivery.setDropContactPhone(drop.contactPhone());
        delivery.setWeightKg(order.estimatedWeightKg());
        delivery.setVolumeCbm(order.estimatedVolumeCbm());
        delivery.setRequiresColdChain(order.requiresColdChain());
        delivery.setVehicleType(VehicleType.fromWeight(order.estimatedWeightKg(), order.requiresColdChain()));
        delivery.setRequestedAt(Instant.now());
        deliveries.save(delivery);

        timeline.record(delivery, "DeliveryRequested", DeliveryStatus.DELIVERY_REQUESTED,
                "Arranging delivery");

        if (mode == DeliveryMode.SUPPLIER_OWN) {
            // The supplier carries it. No quoting, no booking, and the fee is
            // theirs — usually zero, which doc 01 §20 says the restaurant then
            // pays. The delivery goes straight to the state where someone is
            // expected to collect it.
            // What the order was charged, which is zero when the supplier offered free delivery (D-141).
            delivery.setFee(order.deliveryFee() == null ? BigDecimal.ZERO : order.deliveryFee());
            delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
            delivery.setAssignedAt(Instant.now());
            delivery.setDriverName(pickup.contactName());
            delivery.setDriverPhone(pickup.contactPhone());
            deliveries.save(delivery);

            timeline.record(delivery, DeliveryStatus.DRIVER_ASSIGNED.eventName(),
                    DeliveryStatus.DRIVER_ASSIGNED,
                    "The supplier is delivering this order");
            return Prepared.nothingToBook(delivery.getId(), order.orderNumber());
        }

        return Prepared.needsQuotes(delivery.getId(), order.orderNumber(), order.deliveryFee(),
                body.requiredEtaMinutes(), List.of(), "BOOKING", null);
    }

    /**
     * Automated dispatch triggered by warehouse/kitchen events (e.g. SupplierOrderReady).
     * Does not require a user session.
     *
     * <p>{@code REQUIRES_NEW}, never joining the outbox event's transaction (D-194): once the provider has accepted
     * the booking its order exists outside our database. If this ran inside the event's transaction, any later
     * failure there (another listener, the commit itself) would roll back the delivery row and its attempt and
     * ledger rows but not the courier, and the retry would find no delivery and book a second courier. Committed
     * on its own, the retry meets the existing-delivery check below instead. The delivery is committed before the
     * courier is called, and the call runs with no transaction open.
     */
    public DeliveryDtos.DeliveryResponse autoDispatch(Long supplierOrderId) {
        return bookAndRespond(inTx(() -> prepareAutoDispatch(supplierOrderId)));
    }

    private Prepared prepareAutoDispatch(Long supplierOrderId) {
        var order = directory.order(supplierOrderId);
        if (order == null) {
            log.warn("Auto-dispatch ignored: supplier order {} not found", supplierOrderId);
            return null;
        }

        var existing = deliveries.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (existing != null) {
            log.debug("Auto-dispatch: delivery already exists for order {}", supplierOrderId);
            return Prepared.nothingToBook(existing.getId(), order.orderNumber());
        }

        if (!"READY_FOR_PICKUP".equals(order.status())) {
            log.debug("Auto-dispatch: order {} is not ready for pickup (status: {})", supplierOrderId, order.status());
            return null;
        }

        if ("PICKUP".equalsIgnoreCase(order.deliveryMode())) {
            log.info("Auto-dispatch skipped: order {} is customer pickup", supplierOrderId);
            return null;
        }

        var pickup = directory.pickupFor(order.supplierStoreId());
        var drop = directory.dropFor(order.outletId());
        if (pickup == null || drop == null) {
            log.warn("Auto-dispatch: order {} is missing pickup or drop address", supplierOrderId);
            return null;
        }

        var policy = directory.deliveryPolicy(order.supplierStoreId());
        var mode = resolveMode(null, order.deliveryMode(), policy);

        var delivery = new Delivery();
        delivery.setSupplierOrderId(supplierOrderId);
        delivery.setOutletId(order.outletId());
        delivery.setSupplierStoreId(order.supplierStoreId());
        delivery.setMode(mode);
        delivery.setPickupAddress(pickup.address());
        delivery.setPickupLatitude(pickup.latitude());
        delivery.setPickupLongitude(pickup.longitude());
        delivery.setPickupContactName(pickup.contactName());
        delivery.setPickupContactPhone(pickup.contactPhone());
        delivery.setDropAddress(drop.address());
        delivery.setDropLatitude(drop.latitude());
        delivery.setDropLongitude(drop.longitude());
        delivery.setDropContactName(drop.contactName());
        delivery.setDropContactPhone(drop.contactPhone());
        delivery.setWeightKg(order.estimatedWeightKg());
        delivery.setVolumeCbm(order.estimatedVolumeCbm());
        delivery.setRequiresColdChain(order.requiresColdChain());
        delivery.setVehicleType(VehicleType.fromWeight(order.estimatedWeightKg(), order.requiresColdChain()));
        delivery.setRequestedAt(Instant.now());
        deliveries.save(delivery);

        timeline.record(delivery, "DeliveryRequested", DeliveryStatus.DELIVERY_REQUESTED,
                "Automated dispatch initiated");

        if (mode == DeliveryMode.SUPPLIER_OWN) {
            delivery.setFee(order.deliveryFee() == null ? BigDecimal.ZERO : order.deliveryFee());
            delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
            delivery.setAssignedAt(Instant.now());
            delivery.setDriverName(pickup.contactName());
            delivery.setDriverPhone(pickup.contactPhone());
            deliveries.save(delivery);

            timeline.record(delivery, DeliveryStatus.DRIVER_ASSIGNED.eventName(),
                    DeliveryStatus.DRIVER_ASSIGNED,
                    "The supplier is delivering this order");
            return Prepared.nothingToBook(delivery.getId(), order.orderNumber());
        }

        return Prepared.needsQuotes(delivery.getId(), order.orderNumber(), order.deliveryFee(),
                null, List.of(), "BOOKING", null);
    }

    /**
     * Which mode applies.
     *
     * <p>A request may name one, the order may already carry one from checkout, and
     * otherwise the supplier's configuration decides. <b>Own delivery wins the
     * default</b> where it is available: doc 01 §20 has the restaurant paying for
     * delivery unless the supplier absorbs it, so the free option is the one to
     * choose on their behalf.
     */
    private DeliveryMode resolveMode(String requested, String onOrder,
                                     DeliveryDirectory.DeliveryPolicy policy) {

        // What the buyer was told and charged for, read first: it refuses a collected order, and a request cannot
        // override it. Before this, naming a mode skipped the order's own, so a courier could be booked for a pickup or
        // for an order the supplier said they would deliver themselves (D-145).
        DeliveryMode agreed = fromOrder(onOrder);
        DeliveryMode asked = parseMode(requested);
        boolean agreedOnOrder = false;
        if (asked == null) {
            asked = agreed;
            agreedOnOrder = asked != null;
        } else if (agreed != null && asked != agreed) {
            throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE, agreed == DeliveryMode.SUPPLIER_OWN
                    ? "The supplier is delivering this order themselves, so no delivery partner is needed."
                    : "This order was sold with Costonomy delivery and can't be switched to the supplier's own.");
        }

        // A supplier who offered to deliver this order themselves (D-141) agreed to it when they answered, whatever the
        // store's standing switch says now; only a mode picked at dispatch is checked against it.
        if (asked == DeliveryMode.SUPPLIER_OWN && !agreedOnOrder && !policy.ownDeliveryEnabled()) {
            throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                    "This supplier doesn't deliver orders themselves.");
        }
        if (asked == DeliveryMode.COSTONOMY && !policy.costonomyDeliveryEnabled()) {
            throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                    "This supplier doesn't use Mandi delivery.");
        }
        if (asked != null) {
            return asked;
        }

        if (policy.ownDeliveryEnabled()) {
            return DeliveryMode.SUPPLIER_OWN;
        }
        if (policy.costonomyDeliveryEnabled()) {
            return DeliveryMode.COSTONOMY;
        }
        throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                "This supplier has no delivery option configured.");
    }

    /**
     * The order's mode, translated into this module's.
     *
     * <p>Two vocabularies over one column since D-091. The order says how the
     * restaurant chose to receive the goods — including {@code PICKUP}, which is
     * not a delivery at all — and this module only knows who carries them. They
     * were the same word for a while and are not any more, so the mapping is
     * written down rather than left to {@code valueOf} to get wrong.
     */
    private DeliveryMode fromOrder(String onOrder) {
        if (onOrder == null || onOrder.isBlank()) {
            return null;
        }
        return switch (onOrder) {
            case "SUPPLIER_DELIVERY", "SUPPLIER_OWN" -> DeliveryMode.SUPPLIER_OWN;
            case "COSTONOMY_DELIVERY", "COSTONOMY" -> DeliveryMode.COSTONOMY;
            // A collected order has no consignment. Refused here rather than
            // quietly booked, because a courier sent to goods the kitchen is
            // coming for is a cost nobody agreed to.
            case "PICKUP" -> throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                    "This order is being collected, so there is nothing to deliver.");
            default -> null;
        };
    }

    private DeliveryMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DeliveryMode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose SUPPLIER_OWN or COSTONOMY.");
        }
    }

    // ── Reassignment and cancellation ────────────────────────────────────

    /**
     * Send this consignment to a different courier. Doc 06 §7.
     *
     * <p><b>The delivery keeps its identity.</b> Same row, same id, same thing the
     * restaurant is watching — the attempt count goes up and a new
     * {@code delivery_provider_attempt} is written. Creating a second delivery
     * would show two journeys for one consignment and would make delivery failure
     * rates meaningless.
     */
    public DeliveryDtos.DeliveryResponse reassign(Long actorId, Long deliveryId, String reason) {
        var prepared = inTx(() -> prepareReassign(actorId, deliveryId, reason, false));
        executePrepared(prepared);
        return inTx(() -> {
            var delivery = deliveries.findById(deliveryId).orElseThrow();
            if (delivery.getStatus() == DeliveryStatus.PROVIDER_UNAVAILABLE
                    || delivery.getStatus() == DeliveryStatus.QUOTE_FAILED) {
                throw new BusinessException(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                        "No other delivery partner could take this order.");
            }

            auditService.record(actorId, null, "DELIVERY_REASSIGNED", "DELIVERY", deliveryId,
                    null, delivery.getStatus().name(), reason, "API");
            timeline.record(delivery, "DeliveryReassigned", delivery.getStatus(),
                    "Finding another driver");

            return toResponse(delivery, prepared.orderNumber());
        });
    }

    /**
     * The assignment timer and the sweep: hand the delivery to another courier <b>only if</b> it is still waiting for a
     * driver past its deadline, with the order still ready for pickup. Every one of those is checked again under the row
     * lock, because the caller's own look was made before it: a driver assigned between that look and this lock must not
     * be released, and an order cancelled meanwhile must not get a new courier (D-204 audit, gap 2).
     *
     * @return true if another courier was booked; false if there was nothing to do
     */
    public boolean reassignIfAssignmentExpired(Long deliveryId, String reason) {
        var prepared = inTx(() -> prepareReassign(null, deliveryId, reason, true));
        if (prepared == null) {
            return false;
        }
        executePrepared(prepared);
        return inTx(() -> {
            var delivery = deliveries.findById(deliveryId).orElseThrow();
            boolean booked = delivery.getStatus() == DeliveryStatus.PROVIDER_SELECTED;
            auditService.record(null, null, "DELIVERY_REASSIGNED_AUTO", "DELIVERY", deliveryId,
                    null, delivery.getStatus().name(), reason, "SYSTEM");
            timeline.record(delivery, "DeliveryReassigned", delivery.getStatus(),
                    booked ? "Finding another driver" : "No other driver is available yet");
            return booked;
        });
    }

    /**
     * @param onlyIfAssignmentExpired the timer and the sweep: do nothing (return null) unless the delivery is still
     *                                waiting for a driver, past its deadline, for an order still ready for pickup
     */
    private Prepared prepareReassign(Long actorId, Long deliveryId, String reason, boolean onlyIfAssignmentExpired) {
        // Locked, so a manual retry and the automatic one (D-185) cannot both start a booking. Once this commits the
        // lock is gone while the courier is called, so a second caller is stopped by the pending attempt instead.
        var delivery = loadLockedForEitherSide(actorId, deliveryId);
        if (onlyIfAssignmentExpired) {
            if (delivery.getMode() != DeliveryMode.COSTONOMY
                    || delivery.getStatus() != DeliveryStatus.PROVIDER_SELECTED
                    || delivery.getAssignmentDeadline() == null
                    || delivery.getAssignmentDeadline().isAfter(Instant.now())
                    || delivery.isReconciliationRequired()
                    // Releasing a courier clears this id and leaves the status and the old deadline in place until the
                    // next one is booked: a second caller arriving in that window finds no courier to release and must
                    // not release (and audit) anything again.
                    || delivery.getProviderDeliveryId() == null
                    || attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
                return null;
            }
            var ready = directory.order(delivery.getSupplierOrderId());
            if (ready == null || !"READY_FOR_PICKUP".equals(ready.status())) {
                return null;
            }
        }
        delivery.setLastRetryAt(Instant.now());

        if (delivery.getMode() == DeliveryMode.SUPPLIER_OWN) {
            throw new BusinessException(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                    "This order is being delivered by the supplier.");
        }
        if (delivery.isReconciliationRequired()) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This delivery requires manual reconciliation before it can be reassigned.");
        }
        if (delivery.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery is already " + delivery.getStatus() + ".");
        }
        if (delivery.getStatus().isInFlight() || delivery.getStatus() == DeliveryStatus.DELIVERY_FAILED
                || delivery.getPickedUpAt() != null) {
            // Past pickup the goods are on a vehicle (or came back from a failed hand-over). Reassigning would mean
            // asking a second courier to collect something the first one already has. Operations closes it instead.
            throw new BusinessException(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                    "This order has already been picked up.");
        }
        if (attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
            throw new BusinessException(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                    "A delivery partner is already being booked for this order.");
        }

        var order = directory.order(delivery.getSupplierOrderId());
        // Providers already tried are skipped when a booked partner failed and another is wanted. When nobody holds the
        // delivery (no partner was found), that skip would rule out the only partner there is, forever: a retry then
        // asks everyone again, as the automatic retry does (D-185).
        var tried = isNoPartner(delivery) ? List.<String>of() : booking.triedProviders(deliveryId);

        // Stand the current courier down first in DB, so we are not paying two.
        var cancelPlan = releaseCurrentProviderInDb(delivery, reason == null ? "Reassigned" : reason, null);
        deliveries.save(delivery);

        // Excluding the couriers already tried is the whole point: handing it back
        // to the one that just cancelled is not a reassignment.
        boolean usable = !quoting.usableQuotes(deliveryId, tried, delivery.isRequiresColdChain()).isEmpty();
        if (usable) {
            return Prepared.readyToBook(deliveryId, order == null ? null : order.orderNumber(),
                    tried, "REASSIGNMENT", cancelPlan);
        } else {
            return Prepared.needsQuotes(deliveryId, order == null ? null : order.orderNumber(),
                    order == null ? null : order.deliveryFee(), null, tried, "REASSIGNMENT", cancelPlan);
        }
    }

    /**
     * Book the next courier after the provider, not us, let go of a consignment before pickup.
     *
     * <p>Called after the event that recorded it committed, and by the sweep for one that was missed. Its own
     * transaction, because it runs when the original one is already finished. Locks the delivery and checks again:
     * a manual reassign, the retry job or a second call may have got there first, in which case this does nothing.
     * Never throws on "nobody else": the delivery then moves to {@code PROVIDER_UNAVAILABLE} and the no-partner retry
     * (D-185) takes over.
     *
     * @param failedProviderDeliveryId the consignment that stood down; if the delivery has since moved to a different
     *                                 one, this is stale and does nothing. Null skips that check (the sweep).
     * @return true if another courier was booked
     */
    public boolean reassignAfterProviderFailure(Long deliveryId, String failedProviderDeliveryId, String reason) {
        var prepared = inTx(() -> prepareReassignAfterProviderFailure(deliveryId, failedProviderDeliveryId, reason));
        if (prepared == null) {
            return false;
        }
        executePrepared(prepared);
        return inTx(() -> {
            var delivery = deliveries.findById(deliveryId).orElseThrow();
            boolean booked = delivery.getStatus() == DeliveryStatus.PROVIDER_SELECTED;
            auditService.record(null, null, "DELIVERY_REASSIGNED_AUTO", "DELIVERY", deliveryId,
                    null, delivery.getStatus().name(), reason, "SYSTEM");
            timeline.record(delivery, "DeliveryReassigned", delivery.getStatus(),
                    booked ? "Finding another driver" : "No other driver is available yet");
            return booked;
        });
    }

    private Prepared prepareReassignAfterProviderFailure(Long deliveryId, String failedProviderDeliveryId,
                                                         String reason) {
        var delivery = deliveries.lockById(deliveryId).orElse(null);
        if (delivery == null || delivery.getMode() != DeliveryMode.COSTONOMY) {
            return null;
        }
        if (delivery.getStatus() != DeliveryStatus.DRIVER_CANCELLED
                && delivery.getStatus() != DeliveryStatus.PICKUP_FAILED) {
            return null;
        }
        if (failedProviderDeliveryId != null
                && !failedProviderDeliveryId.equals(delivery.getProviderDeliveryId())) {
            return null;
        }
        var order = directory.order(delivery.getSupplierOrderId());
        // The supplier or restaurant may have cancelled the order itself: booking a courier for it would cost money.
        if (order == null || !"READY_FOR_PICKUP".equals(order.status())) {
            return null;
        }

        if (attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
            return null;
        }

        var tried = booking.triedProviders(deliveryId);
        // Stand the old consignment down so it cannot also send a rider (two couriers at one door).
        var cancelPlan = releaseCurrentProviderInDb(delivery, reason == null ? "Courier stood down" : reason, null);
        deliveries.save(delivery);

        boolean usable = !quoting.usableQuotes(deliveryId, tried, delivery.isRequiresColdChain()).isEmpty();
        if (usable) {
            return Prepared.readyToBook(deliveryId, order.orderNumber(), tried, "REASSIGNMENT", cancelPlan);
        } else {
            return Prepared.needsQuotes(deliveryId, order.orderNumber(), order.deliveryFee(), null, tried, "REASSIGNMENT", cancelPlan);
        }
    }

    /**
     * Book a delivery whose quote was saved but whose booking never started: the process stopped between the
     * transaction that quoted it and the call to the courier. Called by the sweep. Checks again under the lock, so a
     * booking that is in progress (a pending attempt) or already done is left alone.
     *
     * @return true if a courier was booked
     */
    public boolean bookQuoted(Long deliveryId) {
        var prepared = inTx(() -> {
            var delivery = deliveries.lockById(deliveryId).orElse(null);
            if (delivery == null || delivery.getMode() != DeliveryMode.COSTONOMY
                    || delivery.getStatus() != DeliveryStatus.QUOTE_RECEIVED
                    // An earlier attempt whose outcome we could not learn may have booked a courier: booking another
                    // could put two at one door. A person settles that first.
                    || delivery.isReconciliationRequired()
                    || attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
                return null;
            }
            return Prepared.readyToBook(deliveryId, null, booking.triedProviders(deliveryId), "BOOKING", null);
        });
        if (prepared == null) {
            return false;
        }
        executePrepared(prepared);
        return inTx(() -> deliveries.findById(deliveryId)
                .map(d -> d.getStatus() == DeliveryStatus.PROVIDER_SELECTED).orElse(false));
    }

    /**
     * Quote and book a delivery whose request was saved but quoting never finished: the process stopped between the
     * transaction that created it and the quote gathering. Called by the sweep.
     *
     * @return true if a courier was booked
     */
    public boolean quoteAndBookUnquoted(Long deliveryId) {
        var prepared = inTx(() -> {
            var delivery = deliveries.lockById(deliveryId).orElse(null);
            if (delivery == null || delivery.getMode() != DeliveryMode.COSTONOMY
                    || delivery.getStatus() != DeliveryStatus.DELIVERY_REQUESTED
                    || attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
                return null;
            }
            var order = directory.order(delivery.getSupplierOrderId());
            if (order == null || !"READY_FOR_PICKUP".equals(order.status())) {
                return null;
            }
            return Prepared.needsQuotes(deliveryId, order.orderNumber(), order.deliveryFee(),
                    null, List.of(), "BOOKING", null);
        });
        if (prepared == null) {
            return false;
        }
        executePrepared(prepared);
        return inTx(() -> deliveries.findById(deliveryId)
                .map(d -> d.getStatus() == DeliveryStatus.PROVIDER_SELECTED).orElse(false));
    }

    // ── No partner found (D-185) ─────────────────────────────────────────

    private static boolean isNoPartner(Delivery delivery) {
        return delivery.getMode() == DeliveryMode.COSTONOMY
                && (delivery.getStatus() == DeliveryStatus.QUOTE_FAILED
                || delivery.getStatus() == DeliveryStatus.PROVIDER_UNAVAILABLE);
    }

    /**
     * One automatic retry. Called by the retry job, which has already claimed it. Locks the delivery and checks again,
     * so a manual retry or a switch that got there first wins; never throws on "still nobody", unlike
     * {@link #reassign}, which would roll back the attempt it just recorded.
     */
    public void retryNoPartner(Long deliveryId) {
        var prepared = inTx(() -> {
            var delivery = deliveries.lockById(deliveryId).orElse(null);
            if (delivery == null || !isNoPartner(delivery)
                    // An earlier booking whose outcome we could not learn may have produced a courier: asking another
                    // could put two at one door. A person settles that first (gap 1).
                    || delivery.isReconciliationRequired()
                    || attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
                return null;
            }
            var order = directory.order(delivery.getSupplierOrderId());
            if (order == null || !"READY_FOR_PICKUP".equals(order.status())) {
                return null;
            }
            var cancelPlan = releaseCurrentProviderInDb(delivery, "Automatic retry", null);
            deliveries.save(delivery);
            return Prepared.needsQuotes(deliveryId, order.orderNumber(), order.deliveryFee(),
                    null, List.of(), "AUTO_RETRY", cancelPlan);
        });
        executePrepared(prepared);
    }

    /** Offer the supplier delivering it themselves, once. The event tells both sides. */
    @Transactional
    public void offerOwnDelivery(Long deliveryId) {
        var delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || deliveries.claimOffer(deliveryId) == 0) {
            return;
        }
        delivery.setOwnDeliveryOfferedAt(Instant.now());
        timeline.record(delivery, "DeliveryOwnDeliveryOffered", delivery.getStatus(),
                "No delivery partner found. The supplier can deliver it themselves.");
    }

    /**
     * The supplier delivers an order that was sold with Costonomy delivery, because no partner was found in time.
     *
     * <p>The same delivery row becomes the supplier's own (D-026, no second row). The delivery charge the buyer
     * agreed to and paid is unchanged: it is not refunded, re-quoted or asked for again, so no money moves and the
     * buyer's total is exactly what they were shown. D-145 still refuses a named mode on a request; this is the only
     * way across, and only once the offer has been made.
     */
    public DeliveryDtos.DeliveryResponse switchToOwn(Long actorId, Long deliveryId) {
        var result = inTx(() -> {
            var found = deliveries.findById(deliveryId)
                    .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
            accessControl.requireScoped(actorId, Permissions.ORDER_READY,
                    ScopeType.SUPPLIER_STORE, found.getSupplierStoreId(), "Delivery");

            // Order first, then delivery; the retry job only ever takes the delivery.
            directory.lockOrder(found.getSupplierOrderId());
            var delivery = deliveries.lockById(deliveryId).orElseThrow();
            if (delivery.getMode() == DeliveryMode.SUPPLIER_OWN) {
                return new SwitchToOwnResult(null, null, true);
            }
            if (delivery.getMode() != DeliveryMode.COSTONOMY || !isNoPartner(delivery)) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        delivery.getStatus().isTerminal() ? "This delivery is already " + delivery.getStatus() + "."
                                : "A delivery partner has been found for this order.");
            }
            if (delivery.isReconciliationRequired()) {
                // A booking whose outcome is unknown may have produced a courier; switching would send a second carrier
                // for the same goods. Our team settles it first.
                throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                        "Our team is checking this delivery's booking. Please wait; this page updates when it is settled.");
            }
            if (delivery.getOwnDeliveryOfferedAt() == null) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "We're still looking for a delivery partner.");
            }
            var order = directory.order(delivery.getSupplierOrderId());
            if (order == null || !"READY_FOR_PICKUP".equals(order.status())) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This order isn't ready for pickup.");
            }

            var pickup = directory.pickupFor(delivery.getSupplierStoreId());
            var cancelPlan = releaseCurrentProviderInDb(delivery, "Supplier is delivering", "SWITCHED_TO_OWN");
            delivery.setMode(DeliveryMode.SUPPLIER_OWN);
            delivery.setFee(order.deliveryFee() == null ? BigDecimal.ZERO : order.deliveryFee());
            delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
            delivery.setAssignedAt(Instant.now());
            delivery.setDriverName(pickup == null ? null : pickup.contactName());
            delivery.setDriverPhone(pickup == null ? null : pickup.contactPhone());
            delivery.setFailureCode(null);
            delivery.setFailureReason(null);
            delivery.setNoPartnerSince(null);
            deliveries.save(delivery);
            directory.setDeliveryMode(delivery.getSupplierOrderId(), "SUPPLIER_DELIVERY");

            timeline.record(delivery, "DeliverySwitchedToSupplier", DeliveryStatus.DRIVER_ASSIGNED,
                    "The supplier is delivering this order");
            auditService.record(actorId, null, "DELIVERY_SWITCHED_TO_OWN", "DELIVERY", deliveryId,
                    DeliveryStatus.QUOTE_FAILED.name(), DeliveryStatus.DRIVER_ASSIGNED.name(),
                    "No delivery partner found", "API");
            return new SwitchToOwnResult(cancelPlan, order.orderNumber(), false);
        });

        if (result.alreadyOwn()) {
            return inTx(() -> toResponse(deliveries.findById(deliveryId).orElseThrow(), null));
        }

        if (result.cancelPlan() != null) {
            cancelAtProvider(result.cancelPlan());
        }

        return inTx(() -> toResponse(deliveries.findById(deliveryId).orElseThrow(), result.orderNumber()));
    }

    public DeliveryDtos.DeliveryResponse cancel(Long actorId, Long deliveryId, String reason) {
        return cancel(actorId, deliveryId, reason, true);
    }

    public DeliveryDtos.DeliveryResponse cancel(Long actorId, Long deliveryId, String reason, Boolean cancelOrder) {
        var cancelPlan = inTx(() -> {
            var delivery = deliveries.lockById(deliveryId)
                    .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

            requireCancelPermission(actorId, delivery.getOutletId(), delivery.getSupplierStoreId(), deliveryId);

            // A delivery cannot be cancelled on its own while its order is still active: the order would stay ready with a
            // cancelled delivery and nothing that can book another courier (reassign refuses a cancelled delivery, and the
            // supplier's takeover needs one that is still searching). The order is cancelled with it, a courier is found
            // by reassign, or the supplier takes over with switch-to-own (D-206).
            if (Boolean.FALSE.equals(cancelOrder)) {
                var order = directory.order(delivery.getSupplierOrderId());
                if (order != null && !List.of("CANCELLED", "DELIVERED", "COMPLETED").contains(order.status())) {
                    throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                            "A delivery can't be cancelled on its own while its order is still active: nothing would be "
                                    + "left to carry it. Cancel the order, ask for another delivery partner, or let the "
                                    + "supplier deliver it themselves.");
                }
            }

            if (delivery.getStatus() == DeliveryStatus.CANCELLED) {
                if (cancelOrder == null || Boolean.TRUE.equals(cancelOrder)) {
                    orders.cancelForOperations(actorId, delivery.getSupplierOrderId(), reason);
                }
                return null;
            }
            if (delivery.getPickedUpAt() != null || delivery.getStatus().isInFlight()
                    || !delivery.getStatus().canTransitionTo(DeliveryStatus.CANCELLED)) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This delivery can't be cancelled once the goods have been picked up.");
            }

            var plan = releaseCurrentProviderInDb(delivery, reason, "CUSTOMER_CANCEL");

            delivery.setStatus(DeliveryStatus.CANCELLED);
            delivery.setCancelledAt(Instant.now());
            delivery.setFailureReason(reason);
            delivery.setReconciliationRequired(false);
            delivery.setReconciliationReason(null);
            deliveries.save(delivery);

            timeline.record(delivery, DeliveryStatus.CANCELLED.eventName(),
                    DeliveryStatus.CANCELLED, reason);
            auditService.record(actorId, null, "DELIVERY_CANCELLED", "DELIVERY", deliveryId,
                    null, DeliveryStatus.CANCELLED.name(), reason, "API");

            if (cancelOrder == null || Boolean.TRUE.equals(cancelOrder)) {
                orders.cancelForOperations(actorId, delivery.getSupplierOrderId(), reason);
            }

            return plan;
        });

        if (cancelPlan != null) {
            cancelAtProvider(cancelPlan);
        }
        payableSync.syncSoon(deliveryId);

        return inTx(() -> toResponse(deliveries.findById(deliveryId).orElseThrow(), null));
    }

    private void requireCancelPermission(Long actorId, Long outletId, Long storeId, Long deliveryId) {
        if (accessControl.has(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null)
                || accessControl.has(actorId, Permissions.ORDER_CANCEL, ScopeType.OUTLET, outletId)
                || accessControl.has(actorId, Permissions.ORDER_CANCEL, ScopeType.SUPPLIER_STORE, storeId)) {
            return;
        }
        log.warn("Permission violation on cancel: user={} delivery={}", actorId, deliveryId);
        throw new BusinessException(ErrorCode.FORBIDDEN, "You do not have permission to cancel this delivery.");
    }

    /**
     * Close the current courier attempt in the database and clear driver fields under lock.
     * Returns a CancelPlan to cancel at the provider outside the transaction, or null if no provider was booked.
     */
    CancelPlan releaseCurrentProviderInDb(Delivery delivery, String reason, String cause) {
        if (delivery.getProviderDeliveryId() == null) {
            return null;
        }
        String providerCode = delivery.getProviderCode();
        String providerDeliveryId = delivery.getProviderDeliveryId();
        Long deliveryId = delivery.getId();

        attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(deliveryId)
                .ifPresent(attempt -> {
                    if ("BOOKED".equals(attempt.getOutcome())) {
                        attempt.setOutcome("CANCELLED");
                        attempt.setFailureReason(reason);
                        attempt.setEndedAt(Instant.now());
                        if (attempt.getEndedCause() == null) {
                            attempt.setEndedCause(cause != null ? cause : inferEndCause(delivery));
                        }
                        attempts.save(attempt);
                    }
                });

        delivery.setDriverName(null);
        delivery.setDriverPhone(null);
        delivery.setDriverVehicle(null);
        // The released consignment's id is now only in the cancel plan. Webhooks and polls find the delivery by
        // provider code and this id, so left in place a late event from the released courier (picked up, delivered)
        // would still move the delivery while its replacement is being booked.
        delivery.setProviderDeliveryId(null);

        return new CancelPlan(deliveryId, providerCode, providerDeliveryId, reason);
    }

    private static String inferEndCause(Delivery delivery) {
        return switch (delivery.getStatus()) {
            case DRIVER_CANCELLED -> "RIDER_CANCELLED";
            case PICKUP_FAILED -> "PICKUP_FAILED";
            case PROVIDER_SELECTED -> delivery.getAssignmentDeadline() != null
                    && !delivery.getAssignmentDeadline().isAfter(Instant.now())
                    ? "ASSIGNMENT_TIMEOUT" : "OPS_REASSIGN";
            default -> "OPS_REASSIGN";
        };
    }

    // ── Supplier own delivery ────────────────────────────────────────────

    /**
     * The supplier reports their own progress. Doc 06 §2.
     *
     * <p>Only on own delivery. On Costonomy delivery this is refused — §23A.38: a
     * supplier cannot claim a pickup or a delivery a courier performed, and the
     * temptation to let them "just mark it delivered" is exactly what that rule
     * exists to prevent.
     */
    @Transactional
    public DeliveryDtos.DeliveryResponse supplierReports(Long actorId, Long deliveryId,
                                                        DeliveryStatus target) {
        var delivery = movementService.reportByDelivery(actorId, deliveryId, target);
        return toResponse(delivery, null);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /**
     * A read. It never reassigns, books or calls a provider: an expired assignment is the sweep's to move (D-204 audit,
     * gap 16), so opening a screen can neither spend money nor release a driver.
     */
    @Transactional(readOnly = true)
    public DeliveryDtos.DeliveryResponse get(Long actorId, Long deliveryId) {
        var delivery = loadForEitherSide(actorId, deliveryId);

        var order = directory.order(delivery.getSupplierOrderId());
        return toResponse(delivery, order == null ? null : order.orderNumber());
    }

    @Transactional(readOnly = true)
    public DeliveryDtos.DeliveryResponse forOrder(Long actorId, Long supplierOrderId) {
        var delivery = deliveries.findBySupplierOrderId(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("Delivery", supplierOrderId));
        return get(actorId, delivery.getId());
    }

    @Transactional(readOnly = true)
    public List<DeliveryDtos.EventResponse> timelineFor(Long actorId, Long deliveryId) {
        loadForEitherSide(actorId, deliveryId);
        return appliedEvents(deliveryId);
    }

    /** The delivery, for internal callers that already did their own access check. */
    @Transactional(readOnly = true)
    public Delivery load(Long deliveryId) {
        return deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
    }

    // ── internals ────────────────────────────────────────────────────────

    private List<DeliveryDtos.EventResponse> appliedEvents(Long deliveryId) {
        return events.findByDeliveryIdOrderByOccurredAtAscIdAsc(deliveryId).stream()
                // Duplicates and out-of-order events stay in the database for
                // diagnosis. A restaurant does not need to see a courier's retries.
                .filter(event -> "APPLIED".equals(event.getDisposition()))
                .map(event -> new DeliveryDtos.EventResponse(
                        event.getId(), event.getEventType(), event.getStatus(),
                        event.getDescription(), event.getOccurredAt()))
                .toList();
    }

    private void requireEitherSide(Long actorId, Long outletId, Long storeId, Long orderId) {
        if (accessControl.has(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null)
                || accessControl.has(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null)
                || accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId)
                || accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, storeId)) {
            return;
        }
        log.warn("Scope violation: user={} SupplierOrder={} — reported as not found",
                actorId, orderId);
        throw new NotFoundException("SupplierOrder", orderId);
    }

    private Delivery loadLockedForEitherSide(Long actorId, Long deliveryId) {
        var delivery = deliveries.lockById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        // A missing actor is reserved for trusted system-triggered waterfall reassignments.
        if (actorId != null) {
            requireEitherSide(actorId, delivery.getOutletId(),
                delivery.getSupplierStoreId(), deliveryId);
        }
        return delivery;
    }

    private Delivery loadForEitherSide(Long actorId, Long deliveryId) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        requireEitherSide(actorId, delivery.getOutletId(),
                delivery.getSupplierStoreId(), deliveryId);
        return delivery;
    }

    DeliveryDtos.DeliveryResponse toResponse(Delivery delivery, String orderNumber) {
        var latest = delivery.getMode().isTracked()
                ? locations.findFirstByDeliveryIdOrderByRecordedAtDescIdDesc(delivery.getId())
                        .orElse(null)
                : null;

        DeliveryDtos.LocationResponse location = null;
        boolean stale = false;
        Integer ageSeconds = null;

        if (latest != null) {
            location = new DeliveryDtos.LocationResponse(latest.getLatitude(),
                    latest.getLongitude(), latest.getBearing(), latest.getRecordedAt());
            long age = Duration.between(latest.getRecordedAt(), Instant.now()).getSeconds();
            ageSeconds = (int) Math.max(0, age);
            // Doc 06 §8: past the freshness threshold the app shows a stale state.
            // The server decides, so every client agrees on what "stale" means.
            stale = age > locationStaleAfter.getSeconds();
        }

        boolean sandboxControls = PidgeSandboxStages.applies(pidgeProperties, delivery);
        return new DeliveryDtos.DeliveryResponse(
                delivery.getId(), delivery.getSupplierOrderId(), orderNumber,
                delivery.getMode(), delivery.getStatus(), delivery.getFee(),
                delivery.getCurrency(), delivery.getPickupAddress(), delivery.getDropAddress(),
                delivery.getDriverName(), delivery.getDriverPhone(), delivery.getDriverVehicle(),
                delivery.getEtaMinutes(), delivery.getEstimatedArrivalAt(),
                delivery.getMode().isTracked() && delivery.getStatus().isTrackable(),
                delivery.getTrackingUrl(),
                location, stale, ageSeconds,
                delivery.getFailureCode(), delivery.getFailureReason(),
                delivery.getRequestedAt(), delivery.getPickedUpAt(), delivery.getDeliveredAt(),
                delivery.getWeightKg(), delivery.getVolumeCbm(),
                delivery.getVehicleType() != null ? delivery.getVehicleType().name() : null,
                appliedEvents(delivery.getId()),
                isNoPartner(delivery) && delivery.getNoPartnerSince() != null
                        ? delivery.getNoPartnerSince().plus(retryWindow) : null,
                isNoPartner(delivery) ? delivery.getNoPartnerSince() : null,
                isNoPartner(delivery) && delivery.getOwnDeliveryOfferedAt() != null && !delivery.isReconciliationRequired(),
                sandboxControls,
                sandboxControls && pidgeProperties.isSandboxAutoAdvanceActive()
                        ? (int) pidgeProperties.getSandboxAutoAdvance().toSeconds() : null,
                latLng(delivery.getPickupLatitude(), delivery.getPickupLongitude()),
                latLng(delivery.getDropLatitude(), delivery.getDropLongitude()),
                delivery.isReconciliationRequired() && !delivery.getStatus().isTerminal());
    }

    private static DeliveryDtos.LatLngResponse latLng(BigDecimal latitude, BigDecimal longitude) {
        return latitude == null || longitude == null
                ? null : new DeliveryDtos.LatLngResponse(latitude, longitude);
    }
}
