package com.costonomy.mp.trust.service;

import com.costonomy.mp.procurement.domain.DeliveryMode;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Facts from other modules that receiving, disputes and ratings need to read.
 *
 * <p>{@link JdbcTemplate} across the module edge, like the other directories.
 */
@Service
@RequiredArgsConstructor
public class TrustDirectory {

    private final JdbcTemplate jdbc;

    public record OrderInfo(
            Long orderId,
            String orderNumber,
            String status,
            Long outletId,
            Long supplierStoreId,
            /** How it travelled, which decides where receiving may happen. D-091. */
            DeliveryMode deliveryMode) {
    }

    public OrderInfo order(Long supplierOrderId) {
        var rows = jdbc.query("""
                select id, order_number, status, outlet_id, supplier_store_id, delivery_mode
                  from supplier_order where id = ?
                """,
                (rs, row) -> new OrderInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5),
                        DeliveryMode.valueOf(rs.getString(6))),
                supplierOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The lines of an order, as accepted.
     *
     * @param acceptedQuantity what the supplier committed to. Null on a line they
     *                         never answered, which cannot happen on a delivered
     *                         order but is not this class's business to assume.
     */
    public record OrderLine(
            Long itemId,
            String productName,
            BigDecimal requestedQuantity,
            BigDecimal acceptedQuantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            BigDecimal doorstepRefundAmount,
            String doorstepRejectionReason,
            /** The line's stored total: the quantity billed, at its price, with GST (D-124). */
            BigDecimal lineTotal,
            boolean catchWeight,
            /** What a weighed catch-weight line is billed for; null until weighed. */
            BigDecimal billableQuantity) {

        /**
         * What the restaurant must account for at the door: the billed weight on a weighed catch-weight
         * line, the accepted quantity otherwise (D-124). Checking against the accepted quantity on a line that
         * weighed lighter would have the buyer enter the shortfall as "missing" and be refunded for it twice.
         */
        public BigDecimal receivableQuantity() {
            if (catchWeight && billableQuantity != null) {
                return billableQuantity;
            }
            return acceptedQuantity == null ? BigDecimal.ZERO : acceptedQuantity;
        }
    }

    public List<OrderLine> linesOf(Long supplierOrderId) {
        List<OrderLine> lines = new ArrayList<>();
        jdbc.query("""
                select i.id, p.name, i.requested_quantity, i.accepted_quantity, i.unit,
                       i.unit_price_snapshot, i.gst_rate_snapshot,
                       i.doorstep_refund_amount, i.doorstep_rejection_reason,
                       i.line_total, i.is_catch_weight, i.billable_quantity
                  from supplier_order_item i
                  join canonical_product p on p.id = i.canonical_product_id
                 where i.supplier_order_id = ? order by i.id
                """,
                rs -> {
                    lines.add(new OrderLine(rs.getLong(1), rs.getString(2),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getString(9),
                            rs.getBigDecimal(10), rs.getBoolean(11), rs.getBigDecimal(12)));
                },
                supplierOrderId);
        return lines;
    }

    /**
     * Record what was actually delivered on a line.
     *
     * <p>{@code fulfilled_quantity} was left null for exactly this moment — see
     * V10. It <b>adds</b> to the line rather than overwriting it: the accepted
     * quantity stays as the supplier committed to it, which doc 03 §11 requires and
     * which every dispute is argued from. This is also what makes a real fill rate
     * possible, closing the gap D-019 left open.
     */
    public void recordFulfilled(Long supplierOrderItemId, BigDecimal fulfilledQuantity) {
        jdbc.update("""
                update supplier_order_item
                   set fulfilled_quantity = ?
                 where id = ?
                """, fulfilledQuantity, supplierOrderItemId);
    }

    public void recordDoorstepReconciliation(Long supplierOrderItemId, BigDecimal acceptedQty,
                                             BigDecimal rejectedQty, String reason, BigDecimal refundAmount) {
        jdbc.update("""
                update supplier_order_item
                   set doorstep_accepted_qty = ?,
                       doorstep_rejected_qty = ?,
                       doorstep_rejection_reason = ?,
                       doorstep_refund_amount = ?
                 where id = ?
                """, acceptedQty, rejectedQty, reason, refundAmount, supplierOrderItemId);
    }

    /**
     * Record what a doorstep rejection takes off the order, and return what the order now comes to.
     *
     * <p>Guarded in the statement: the final payable may not go below zero, and exactly one row must change.
     * A negative final would be clamped to zero downstream and the platform would fund the excess (D-124), so
     * it is refused here, where the figure is written.
     *
     * @return the order's final payable after the rejection
     */
    public BigDecimal updateOrderFinancialReconciliation(Long supplierOrderId, BigDecimal doorstepRefundAmount) {
        int updated = jdbc.update("""
                update supplier_order
                   set doorstep_refund_amount = ?,
                       final_payable_amount = accepted_amount - coalesce(weight_adjustment_amount, 0) - ?
                 where id = ?
                   and accepted_amount - coalesce(weight_adjustment_amount, 0) - ? >= 0
                """, doorstepRefundAmount, doorstepRefundAmount, supplierOrderId, doorstepRefundAmount);
        if (updated != 1) {
            throw new com.costonomy.mp.common.error.BusinessException(
                    com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                    "The rejected goods are worth more than this order's payable amount.");
        }
        return jdbc.queryForObject("select final_payable_amount from supplier_order where id = ?",
                BigDecimal.class, supplierOrderId);
    }

    /** The credit note raised for this order's doorstep rejection, or null when none exists (D-124). */
    public String creditNoteNumberFor(Long supplierOrderId) {
        var numbers = jdbc.queryForList(
                "select credit_note_number from credit_note where supplier_order_id = ? order by id limit 1",
                String.class, supplierOrderId);
        return numbers.isEmpty() ? null : numbers.get(0);
    }

    /**
     * Move the order to COMPLETED, guarded on where it is allowed to come from.
     *
     * <p>Two origins since D-091. A delivered order completes from
     * {@code DELIVERED}; a {@code PICKUP} order never reaches that status and
     * completes from {@code READY_FOR_PICKUP}, when the restaurant confirms what
     * they collected.
     *
     * <p>Compare-and-set rather than a read-then-write, so two confirmations of
     * the same collection settle to one.
     */
    public boolean completeOrder(Long supplierOrderId, SupplierOrderStatus from) {
        return jdbc.update("""
                update supplier_order
                   set status = ?,
                       final_payable_amount = coalesce(final_payable_amount, accepted_amount - coalesce(weight_adjustment_amount, 0) - coalesce(doorstep_refund_amount, 0)),
                       version = version + 1, updated_at = now(6)
                 where id = ? and status = ?
                """, SupplierOrderStatus.COMPLETED.name(), supplierOrderId, from.name()) == 1;
    }
}
