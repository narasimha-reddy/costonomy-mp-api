package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.procurement.domain.*;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The supplier's response to an order. Doc 03 §5, doc 13, doc 14.
 *
 * <p>This class exists to get one thing right: <b>acceptance and expiry race, and
 * exactly one wins.</b> Doc 03 §5 and doc 10 §2 both require it, and it is not a
 * theoretical race — a 60-second window means a supplier tapping Accept as the
 * timeout job sweeps is an ordinary Tuesday.
 *
 * <p>The mechanism is optimistic locking on {@code SupplierOrder}. Both the
 * supplier's request and the job read the same version; the second write fails.
 * What this class adds on top is <b>reporting the outcome honestly</b>: when a
 * supplier loses the race, they are told their order expired
 * ({@code SUPPLIER_ORDER_EXPIRED}), not that a concurrent modification occurred.
 * The second is true and useless.
 *
 * <p>Every transition is idempotent (doc 04 §21), because a supplier tapping
 * Accept twice on a slow connection must not produce two acceptances — and
 * because the second tap arriving after the first succeeded should return the
 * same answer rather than an error.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierOrderService {

    private final SupplierOrderRepository orders;
    private final SupplierOrderItemRepository orderItems;
    private final SupplierOrderTransitions transitions;
    private final SupplierOrderMapper mapper;
    private final ProcurementDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;

    // ── Supplier inbox ───────────────────────────────────────────────────

    /**
     * Orders the supplier has not started on. Doc 05 §24.
     *
     * <p><b>Not "awaiting a response" any more.</b> D-091 removed the second
     * acceptance: these arrive {@code CONFIRMED}, already agreed to on the
     * request and already paid for, so what makes them urgent is that nobody has
     * begun preparing them — not that an answer is overdue.
     */
    @Transactional(readOnly = true)
    public List<ProcurementDtos.IncomingOrderResponse> pendingForStore(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        Instant now = Instant.now();
        return orders.findBySupplierStoreIdAndStatusInOrderByAcceptanceDeadlineAsc(
                        storeId, List.of(SupplierOrderStatus.CONFIRMED))
                .stream()
                .map(order -> toIncoming(order, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProcurementDtos.IncomingOrderResponse> activeForStore(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        Instant now = Instant.now();
        return orders.findBySupplierStoreIdAndStatusInOrderByAcceptanceDeadlineAsc(
                        storeId, List.of(
                                SupplierOrderStatus.CONFIRMED,
                                SupplierOrderStatus.PREPARING,
                                SupplierOrderStatus.READY_FOR_PICKUP,
                                SupplierOrderStatus.OUT_FOR_DELIVERY))
                .stream()
                .map(order -> toIncoming(order, now))
                .toList();
    }

    // ── Working the order ────────────────────────────────────────────────
    //
    // No accept, no partial accept, no decline. D-091: the supplier committed on
    // the request, so an order is theirs to prepare or -- if they turn out not to
    // be able to -- to cancel, which is a refund rather than a rejection.

    /** Move an accepted order into preparation. Doc 05 §28. */
    public ProcurementDtos.SupplierOrderResponse markPreparing(
            Long actorId, Long orderId, String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.preparing", idempotencyKey,
                Map.of("orderId", orderId),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.advance(actorId, orderId, SupplierOrderStatus.PREPARING,
                        Permissions.ORDER_PREPARE));
    }

    /**
     * Mark ready for pickup.
     *
     * <p>Doc 06 §6: this is what starts delivery. Once it is set, the delivery
     * provider owns the states that follow — §23A.38 is explicit that a supplier
     * must not be able to claim pickup or delivery on a provider's behalf, which
     * is why {@link SupplierOrderStatus} gives them no transition past this point.
     */
    public ProcurementDtos.SupplierOrderResponse markReady(
            Long actorId, Long orderId, String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.ready", idempotencyKey,
                Map.of("orderId", orderId),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.advance(actorId, orderId, SupplierOrderStatus.READY_FOR_PICKUP,
                        Permissions.ORDER_READY));
    }

    /**
     * Mark out for delivery, as the supplier carrying it.
     *
     * <p>Refused under {@code COSTONOMY_DELIVERY} by
     * {@code SupplierOrderTransitions}, where the courier's events are the only
     * evidence that a van left (§23A.38).
     */
    public ProcurementDtos.SupplierOrderResponse markOutForDelivery(
            Long actorId, Long orderId, String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.outForDelivery", idempotencyKey,
                Map.of("orderId", orderId),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.advance(actorId, orderId,
                        SupplierOrderStatus.OUT_FOR_DELIVERY, Permissions.ORDER_READY));
    }

    /**
     * Mark delivered, as the supplier who carried it.
     *
     * <p>Delivered is not completed: the restaurant confirms what actually
     * arrived, through receiving, and that is what finishes the order. A supplier
     * saying "delivered" is one side's account of it.
     */
    public ProcurementDtos.SupplierOrderResponse markDelivered(
            Long actorId, Long orderId, String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.delivered", idempotencyKey,
                Map.of("orderId", orderId),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.advance(actorId, orderId,
                        SupplierOrderStatus.DELIVERED, Permissions.ORDER_READY));
    }

    /**
     * Every status a supplier may see.
     *
     * <p><b>DRAFT is absent, deliberately.</b> An order is created DRAFT and stays
     * invisible until it is funded (guardrail 16, D-020), so "all orders" is this
     * list rather than the absence of a filter — a query written as "no status
     * clause" would leak unfunded orders the first time somebody added a tab.
     */
    private static final List<SupplierOrderStatus> VISIBLE = List.of(
            SupplierOrderStatus.CONFIRMED,
            SupplierOrderStatus.PREPARING,
            SupplierOrderStatus.READY_FOR_PICKUP,
            SupplierOrderStatus.OUT_FOR_DELIVERY,
            SupplierOrderStatus.DELIVERED,
            SupplierOrderStatus.COMPLETED,
            SupplierOrderStatus.CANCELLED);

    /** How far back a window reaches when the caller does not say. */
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    /**
     * A store's order history, by status and by when the order arrived.
     *
     * <p>Dated on {@code createdAt} rather than on any status timestamp, because
     * that is the one date every order has and the one a supplier means by "last
     * week". Sorting the same way keeps a list stable while orders move through
     * their lifecycle underneath it.
     *
     * <p>An absent window defaults to seven days rather than to everything: an
     * unbounded history is a table scan that grows with the marketplace, and it
     * is not what anyone opening a list wants to wait for.
     */
    @Transactional(readOnly = true)
    public List<ProcurementDtos.IncomingOrderResponse> historyForStore(
            Long actorId, Long storeId,
            List<SupplierOrderStatus> statuses, Instant from, Instant to) {

        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST,
                    "The start of the range is after its end.");
        }

        // Intersected with VISIBLE rather than used as given: a caller asking for
        // DRAFT gets nothing back instead of an unfunded order.
        List<SupplierOrderStatus> wanted = statuses == null || statuses.isEmpty()
                ? VISIBLE
                : statuses.stream().filter(VISIBLE::contains).toList();
        if (wanted.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        return orders
                .findBySupplierStoreIdAndStatusInAndCreatedAtBetweenOrderByCreatedAtDesc(
                        storeId, wanted, start, end)
                .stream()
                .map(order -> toIncoming(order, now))
                .toList();
    }

    // ── Restaurant side ──────────────────────────────────────────────────

    /**
     * Cancel, where policy permits. Doc 01 §13, D-091.
     *
     * <p>Impossible once the goods have left, which the state machine enforces
     * rather than this method. {@code by} is recorded rather than inferred: this
     * is the supplier's only way out of an order they have been paid for, and a
     * reliability figure that could not tell the two apart would blame whichever
     * side the query happened to assume.
     */
    public ProcurementDtos.SupplierOrderResponse cancel(
            Long actorId, Long orderId, String reason, CancelledBy by, String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.cancel", idempotencyKey,
                Map.of("orderId", orderId, "by", by.name()),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.cancel(actorId, orderId, reason, by));
    }

    // ── internals ────────────────────────────────────────────────────────

    private ProcurementDtos.IncomingOrderResponse toIncoming(SupplierOrder order, Instant now) {
        var full = mapper.toResponse(order);
        var outlet = directory.outletSummary(order.getOutletId());

        long remaining = order.getAcceptanceDeadline() == null ? 0L
                : Math.max(0L, java.time.Duration.between(now, order.getAcceptanceDeadline())
                        .toSeconds());

        return new ProcurementDtos.IncomingOrderResponse(
                order.getId(), order.getOrderNumber(), order.getOutletId(),
                outlet == null ? null : outlet.outletName(),
                outlet == null ? null : outlet.restaurantName(),
                full.outletLocality(), full.outletCity(), full.distanceKm(),
                order.getStatus(), order.getAcceptanceDeadline(), order.getResponseSlaSeconds(),
                remaining, order.getCreatedAt(),
                order.getSubtotal(), order.getGstAmount(), order.getTotalAmount(),
                order.getAcceptedAmount(), order.getPaymentMethod(), full.items());
    }

    @Transactional(readOnly = true)
    public SupplierOrder load(Long orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));
    }
}
