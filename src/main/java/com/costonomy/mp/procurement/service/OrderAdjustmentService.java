package com.costonomy.mp.procurement.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.procurement.domain.OrderAdjustment;
import com.costonomy.mp.procurement.domain.OrderAdjustmentReason;
import com.costonomy.mp.procurement.domain.OrderAdjustmentStatus;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.repository.OrderAdjustmentRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Every reduction of what an order comes to after it settles at ready is a row (D-129).
 *
 * <p>After ready, {@code final_payable_amount = accepted_amount - sum(adjustments)}, written in the same
 * transaction as the funding call that moved the money. A row is never deleted; only a pending one is applied.
 *
 * <p><b>Lock order is order, then adjustment, then wallet, then payment</b>, everywhere. The order is locked
 * first by every caller (the ready transition, receiving, the job), the funding adapters take the wallet and the
 * payment after it, and the capture job locks the payment alone, so none of them can deadlock with another.
 *
 * <p><b>The order is written and flushed before the funding call.</b> A wallet credit is a bulk update that
 * empties the persistence context, so anything changed on the order entity afterwards would be lost. The
 * adjustment row is inserted after the call, once its outcome is known: it is a new entity, so the clearing does
 * not touch it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderAdjustmentService {

    private final SupplierOrderRepository orders;
    private final OrderAdjustmentRepository adjustments;
    private final OrderFunding funding;
    private final AuditService auditService;

    /** What recording a doorstep rejection did: the row's state, and what the order now comes to. */
    public record DoorstepOutcome(OrderAdjustmentStatus status, BigDecimal finalPayable) {
    }

    /**
     * The order is going ready: settle its money to the final payable, once, and record the catch-weight
     * shortfall as a row. Called by the ready transition with the order already locked.
     */
    @Transactional
    public void settleAtReady(SupplierOrder lockedOrder, Long actorId) {
        BigDecimal accepted = lockedOrder.getAcceptedAmount();
        BigDecimal settled = lockedOrder.getFinalPayableAmount() != null
                ? lockedOrder.getFinalPayableAmount() : accepted;
        lockedOrder.setFinalPayableAmount(Pricing.money(settled));
        orders.saveAndFlush(lockedOrder);

        BigDecimal shortfall = accepted.subtract(settled).max(BigDecimal.ZERO);
        var reduction = funding.onOrderDispatched(lockedOrder.getId(), settled, shortfall);

        String key = "weight-settle-" + lockedOrder.getId();
        if (shortfall.signum() > 0
                && adjustments.findBySupplierOrderIdAndReason(lockedOrder.getId(),
                OrderAdjustmentReason.WEIGHT_SETTLEMENT).isEmpty()) {
            var row = OrderAdjustment.of(lockedOrder.getId(), OrderAdjustmentReason.WEIGHT_SETTLEMENT,
                    Pricing.money(shortfall), key, lockedOrder.getPaymentMethod(),
                    "Catch-weight shortfall settled at ready", actorId);
            row.markApplied(reduction.fundingReference(), reduction.settledOutside());
            adjustments.save(row);
            audit(actorId, lockedOrder.getId(), row);
        }
    }

    /**
     * Goods were rejected at the door: reduce what the order comes to, and give the money back by the order's
     * funding method (D-129). On a card whose capture is still pending the refund waits for the capture: the final
     * payable is reduced now and the row is written {@code PENDING_CAPTURE}.
     *
     * <p>Idempotent: a second call returns the first one's outcome.
     */
    @Transactional
    public DoorstepOutcome recordDoorstepRejection(Long orderId, BigDecimal amount, Long actorId, String note) {
        var order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException("SupplierOrder", orderId));

        var existing = adjustments.findBySupplierOrderIdAndReason(orderId, OrderAdjustmentReason.DOORSTEP_REJECTION);
        if (existing.isPresent()) {
            return new DoorstepOutcome(existing.get().getStatus(), order.getFinalPayableAmount());
        }

        BigDecimal newFinal = order.getAcceptedAmount().subtract(adjustments.sumForOrder(orderId)).subtract(amount);
        if (newFinal.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The rejected goods are worth more than this order's payable amount.");
        }
        order.setDoorstepRefundAmount(Pricing.money(amount));
        order.setFinalPayableAmount(Pricing.money(newFinal));
        orders.saveAndFlush(order);

        String key = "doorstep-" + orderId;
        var reduction = funding.reduceAfterDispatch(orderId, amount, newFinal, key, actorId, note);

        var row = OrderAdjustment.of(orderId, OrderAdjustmentReason.DOORSTEP_REJECTION, Pricing.money(amount), key,
                order.getPaymentMethod(), note, actorId);
        if (reduction.isApplied()) {
            row.markApplied(reduction.fundingReference(), reduction.settledOutside());
        } else {
            row.markPendingCapture();
        }
        adjustments.save(row);
        audit(actorId, orderId, row);
        return new DoorstepOutcome(row.getStatus(), Pricing.money(newFinal));
    }

    /**
     * Apply a pending adjustment now that its payment may be captured. Used by the job.
     *
     * <p>Locks the order first, then the row, and does not write {@code supplier_order}: settlement reads that
     * table's {@code updated_at} as the completion time, so touching it would move the order's settlement window.
     *
     * @return true if the money moved on this call
     */
    @Transactional
    public boolean applyPending(Long adjustmentId) {
        var peek = adjustments.findById(adjustmentId).orElse(null);
        if (peek == null || peek.getStatus() != OrderAdjustmentStatus.PENDING_CAPTURE) {
            return false;
        }
        var order = orders.lockById(peek.getSupplierOrderId()).orElse(null);
        if (order == null) {
            return false;
        }
        var row = adjustments.lockById(adjustmentId).orElse(null);
        if (row == null || row.getStatus() != OrderAdjustmentStatus.PENDING_CAPTURE) {
            return false;
        }
        var reduction = funding.reduceAfterDispatch(row.getSupplierOrderId(), row.getAmount(),
                order.getFinalPayableAmount(), row.getIdempotencyKey(), row.getCreatedBy(), row.getNote());
        if (!reduction.isApplied()) {
            return false;
        }
        row.markApplied(reduction.fundingReference(), reduction.settledOutside());
        adjustments.save(row);
        auditService.record(row.getCreatedBy(), null, "ORDER_ADJUSTMENT_APPLIED", "SUPPLIER_ORDER",
                row.getSupplierOrderId(), OrderAdjustmentStatus.PENDING_CAPTURE.name(),
                OrderAdjustmentStatus.APPLIED.name(), row.getReason() + " " + row.getAmount().toPlainString(), "SYSTEM");
        return true;
    }

    /** The doorstep rejection's state, if the order has one: for the receiving response. */
    @Transactional(readOnly = true)
    public Optional<String> doorstepStatus(Long orderId) {
        return adjustments.findBySupplierOrderIdAndReason(orderId, OrderAdjustmentReason.DOORSTEP_REJECTION)
                .map(row -> row.getStatus().name());
    }

    @Transactional(readOnly = true)
    public List<Long> pendingIds(int limit) {
        return adjustments.pendingIds(PageRequest.of(0, limit));
    }

    private void audit(Long actorId, Long orderId, OrderAdjustment row) {
        auditService.record(actorId, null, "ORDER_ADJUSTMENT_RECORDED", "SUPPLIER_ORDER", orderId, null,
                row.getStatus().name(),
                "%s %s %s%s".formatted(row.getReason(), row.getAmount().toPlainString(), row.getStatus(),
                        row.getSettledOutsideAmount().signum() > 0
                                ? " [settled directly " + row.getSettledOutsideAmount().toPlainString() + "]" : ""),
                actorId == null ? "SYSTEM" : "API");
    }
}
