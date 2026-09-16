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
     * Orders awaiting a response, soonest deadline first. Doc 05 §24.
     *
     * <p>{@code secondsRemaining} is computed here, from the authoritative
     * deadline, so the client starts its countdown from the server's view of the
     * time rather than the handset's (§23A.34, doc 13).
     */
    @Transactional(readOnly = true)
    public List<ProcurementDtos.IncomingOrderResponse> pendingForStore(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        Instant now = Instant.now();
        return orders.findBySupplierStoreIdAndStatusInOrderByAcceptanceDeadlineAsc(
                        storeId, List.of(SupplierOrderStatus.PENDING_ACCEPTANCE))
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
                                SupplierOrderStatus.PARTIALLY_ACCEPTED,
                                SupplierOrderStatus.PREPARING,
                                SupplierOrderStatus.READY_FOR_PICKUP))
                .stream()
                .map(order -> toIncoming(order, now))
                .toList();
    }

    // ── Responding ───────────────────────────────────────────────────────

    /** Accept in full. */
    public ProcurementDtos.SupplierOrderResponse accept(
            Long actorId, Long orderId, String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.accept", idempotencyKey,
                Map.of("orderId", orderId),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.accept(actorId, orderId));
    }

    /**
     * Accept reduced quantities. Doc 14.
     *
     * <p>Every line must be answered, including with zero. Doc 04 §11 makes a zero
     * explicit, and an omitted line would be ambiguous between "declined" and
     * "forgot" — a distinction the restaurant needs, because the shortfall returns
     * to their requirement either way but the reason does not.
     */
    public ProcurementDtos.SupplierOrderResponse partialAccept(
            Long actorId, Long orderId, ProcurementDtos.PartialAcceptRequest request,
            String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.partialAccept", idempotencyKey,
                Map.of("orderId", orderId, "items", request.items()),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.partialAccept(actorId, orderId, request));
    }

    /** Decline outright, with a reason. */
    public ProcurementDtos.SupplierOrderResponse reject(
            Long actorId, Long orderId, ProcurementDtos.RejectOrderRequest request,
            String idempotencyKey) {

        if (!RejectionReason.isValid(request.reason())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose one of the listed rejection reasons.");
        }

        return idempotency.execute(actorId, "supplierOrder.reject", idempotencyKey,
                Map.of("orderId", orderId, "reason", request.reason()),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.reject(actorId, orderId, request));
    }

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

    // ── Restaurant side ──────────────────────────────────────────────────

    /**
     * Cancel, where policy permits. Doc 01 §13.
     *
     * <p>Free before the supplier has accepted; conditional after. Impossible once
     * the goods have left, which the state machine enforces rather than this method.
     */
    public ProcurementDtos.SupplierOrderResponse cancel(
            Long actorId, Long orderId, String reason, String idempotencyKey) {

        return idempotency.execute(actorId, "supplierOrder.cancel", idempotencyKey,
                Map.of("orderId", orderId),
                ProcurementDtos.SupplierOrderResponse.class,
                () -> transitions.cancel(actorId, orderId, reason));
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
                remaining, order.getSubtotal(), order.getGstAmount(), order.getTotalAmount(),
                order.getAcceptedAmount(), order.getPaymentMethod(), full.items());
    }

    @Transactional(readOnly = true)
    public SupplierOrder load(Long orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));
    }
}
