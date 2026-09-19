package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.procurement.domain.*;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Moving a supplier order along. D-091.
 *
 * <p>Separate bean, so the {@code @Transactional} boundaries are real when called
 * through the idempotency wrapper — see D-016 and the three other stores that
 * exist for the same reason.
 *
 * <p><b>There is no acceptance here any more.</b> D-088 moved the supplier's
 * commitment to the request and D-091 removed the four statuses that asked them
 * again. An order arrives {@code CONFIRMED} because they already said yes and the
 * restaurant paid against that answer, so what remains is movement — and
 * cancellation, for a supplier who turns out not to be able to fulfil after all.
 *
 * <p><b>Who may move it depends on how the goods travel.</b> Under
 * {@code SUPPLIER_DELIVERY} the supplier is the courier and sets
 * {@code OUT_FOR_DELIVERY} and {@code DELIVERED} themselves. Under
 * {@code COSTONOMY_DELIVERY} those come from the provider's events and from
 * nowhere else — §23A.38, a supplier cannot claim movement on a courier's behalf.
 * Under {@code PICKUP} neither happens: the restaurant collects, and the order
 * completes through receiving.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierOrderTransitions {

    private final SupplierOrderRepository orders;
    private final SupplierOrderMapper mapper;
    private final OrderFunding funding;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final ProcurementDirectory directory;

    /**
     * The supplier's display name, for the notification a restaurant reads.
     *
     * <p>Every event below carries `supplierStoreId`, which is what downstream
     * modules key on — but doc 08's templates name the supplier, and an id cannot
     * fill that. Without the name the renderer drops the placeholder and the
     * restaurant is told " is preparing order MP-…", which reads as a bug because
     * it is one.
     *
     * <p>Empty rather than a guess when the store has vanished: the renderer
     * already collapses an unresolved placeholder, and inventing "Your supplier"
     * here would put words in a real company's mouth.
     */
    private String supplierNameOf(Long storeId) {
        var store = directory.stores(java.util.List.of(storeId)).get(storeId);
        return store == null ? "" : store.storeName();
    }

    /**
     * Move the order on, as the supplier.
     *
     * <p>The mode is read from the order rather than passed in, for the reason
     * {@code OrderReleaseService} learned the hard way: a caller-supplied target
     * is a caller's opinion, and the callers here — a controller, a job, a
     * provider bridge — do not all know how this consignment travels.
     */
    @Transactional
    public ProcurementDtos.SupplierOrderResponse advance(
            Long actorId, Long orderId, SupplierOrderStatus target, String permission) {

        var order = loadForSupplier(actorId, orderId, permission);
        var mode = order.getDeliveryMode();

        if (order.getStatus() == target) {
            return mapper.toResponse(order);
        }

        // §23A.38. Under COSTONOMY_DELIVERY the courier's events are the only
        // thing that may report movement, and a supplier pressing a button is not
        // evidence that a van left.
        if (mode.isCourierCarried() && (target == SupplierOrderStatus.OUT_FOR_DELIVERY
                || target == SupplierOrderStatus.DELIVERED)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "A courier is carrying this order, so its movement is tracked for you.");
        }

        var current = order.getStatus();
        if (!current.canTransitionTo(target, mode)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order can't move from %s to %s.".formatted(current, target));
        }

        order.setStatus(target);
        save(order, current);

        auditService.record(actorId, null, "SUPPLIER_ORDER_" + target.name(), "SUPPLIER_ORDER",
                orderId, current.name(), target.name(), null, "API");

        outbox.publish(eventFor(target), "SUPPLIER_ORDER", orderId,
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierName", supplierNameOf(order.getSupplierStoreId()),
                        "supplierStoreId", order.getSupplierStoreId(),
                        "deliveryMode", mode.name(),
                        "outletId", order.getOutletId()),
                actorId);

        return mapper.toResponse(order);
    }

    /**
     * What the restaurant is told, per step.
     *
     * <p>"Ready" means something different depending on the mode — a van is
     * leaving, or there are crates waiting on a counter for somebody to fetch —
     * and the notification templates need to say which.
     */
    private static String eventFor(SupplierOrderStatus target) {
        return switch (target) {
            case PREPARING -> "SupplierOrderPreparing";
            case READY_FOR_PICKUP -> "SupplierOrderReady";
            case OUT_FOR_DELIVERY -> "SupplierOrderOutForDelivery";
            case DELIVERED -> "SupplierOrderDelivered";
            default -> "SupplierOrderMoved";
        };
    }

    /**
     * End the order before the goods move, and record whose decision it was.
     *
     * <p>This is what {@code REJECTED} used to be on the supplier's side. After
     * D-088 the supplier has already committed and been paid against, so backing
     * out is a cancellation with a refund — {@link CancelledBy} keeps the two
     * apart for the reliability figures without a second terminal status.
     */
    @Transactional
    public ProcurementDtos.SupplierOrderResponse cancel(
            Long actorId, Long orderId, String reason, CancelledBy by) {

        var order = orders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));

        // Each party cancels within their own scope. A supplier cannot cancel on
        // the restaurant's behalf and the reverse holds too, which is also why the
        // attribution cannot simply be inferred at read time.
        if (by == CancelledBy.SUPPLIER) {
            // ORDER_REJECT, not ORDER_CANCEL. The latter is the restaurant's
            // permission and is granted at outlet scope; this is the same act
            // the supplier's rejection permission always covered — backing out
            // of an order they cannot serve — and D-091 only changed what it is
            // called and that it refunds.
            accessControl.requireScoped(actorId, Permissions.ORDER_REJECT,
                    ScopeType.SUPPLIER_STORE, order.getSupplierStoreId(), "SupplierOrder");
        } else {
            accessControl.requireScoped(actorId, Permissions.ORDER_CANCEL,
                    ScopeType.OUTLET, order.getOutletId(), "SupplierOrder");
        }

        if (order.getStatus() == SupplierOrderStatus.CANCELLED) {
            return mapper.toResponse(order);
        }

        var current = order.getStatus();
        if (!current.canTransitionTo(SupplierOrderStatus.CANCELLED, order.getDeliveryMode())) {
            // Doc 01 §13: once the goods have left, the path is return or dispute.
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order can no longer be cancelled.");
        }

        order.setStatus(SupplierOrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        order.setCancelledBy(by);
        order.setCancellationReason(reason);
        save(order, current);

        funding.onOrderUnfulfilled(order.getId(), "Cancelled: " + reason);

        auditService.record(actorId, null, "SUPPLIER_ORDER_CANCELLED", "SUPPLIER_ORDER",
                orderId, current.name(), SupplierOrderStatus.CANCELLED.name(), reason, "API");

        outbox.publish("SupplierOrderCancelled", "SUPPLIER_ORDER", orderId,
                Map.of("orderNumber", order.getOrderNumber(),
                        "supplierName", supplierNameOf(order.getSupplierStoreId()),
                        "supplierStoreId", order.getSupplierStoreId(),
                        "cancelledBy", by.name(),
                        "outletId", order.getOutletId()),
                actorId);

        return mapper.toResponse(order);
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Persist, translating a lost race into the outcome that actually occurred.
     *
     * <p>Much less can race now that nothing expires an order: what remains is two
     * people acting on the same order at once — a supplier marking it ready while
     * the restaurant cancels it. The loser is told what won, not that a version
     * column disagreed.
     */
    private void save(SupplierOrder order, SupplierOrderStatus expectedPrevious) {
        try {
            orders.saveAndFlush(order);
        } catch (OptimisticLockingFailureException ex) {
            Long id = order.getId();
            log.info("Lost the race on supplier order {} — reporting the winning outcome", id);

            // Read the winner in a fresh state. The entity in hand is stale by
            // definition, so nothing on it can be trusted here.
            var winner = orders.findById(id).orElseThrow(
                    () -> new NotFoundException("SupplierOrder", id));

            throw switch (winner.getStatus()) {
                case CANCELLED -> new BusinessException(ErrorCode.SUPPLIER_ORDER_ALREADY_RESOLVED,
                        winner.getCancelledBy() == CancelledBy.SUPPLIER
                                ? "This order was cancelled by the supplier."
                                : "This order was cancelled by the restaurant.");
                default -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
            };
        }
    }

    private SupplierOrder loadForSupplier(Long actorId, Long orderId, String permission) {
        var order = orders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));

        accessControl.requireScoped(actorId, permission,
                ScopeType.SUPPLIER_STORE, order.getSupplierStoreId(), "SupplierOrder");
        return order;
    }
}
