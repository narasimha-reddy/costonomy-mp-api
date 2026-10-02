package com.costonomy.mp.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Facts from other modules that settlement needs to read.
 *
 * <p>{@link JdbcTemplate} across the module edge, like the other directories.
 */
@Service
@RequiredArgsConstructor
public class SettlementDirectory {

    private final JdbcTemplate jdbc;

    /**
     * An order that is finished and owes commission.
     *
     * @param acceptedAmount what the supplier committed to and what was captured
     * @param deliveryFee    excluded from the commission base (doc 01 §16)
     */
    public record SettleableOrder(
            Long orderId,
            Long supplierStoreId,
            Long supplierOrganizationId,
            BigDecimal acceptedAmount,
            BigDecimal deliveryFee,
            Instant completedAt) {
    }

    /**
     * Orders completed in a window that have no commission calculation yet.
     *
     * <p><b>COMPLETED, not DELIVERED.</b> An order is settled once the restaurant
     * has checked it in: that is the last moment a shortfall can surface, and
     * paying a supplier before anyone has counted the goods would mean clawing it
     * back through an adjustment in the common case rather than the rare one.
     */
    public List<SettleableOrder> settleableOrders(Instant from, Instant to) {
        List<SettleableOrder> orders = new ArrayList<>();
        jdbc.query("""
                select so.id, so.supplier_store_id, ss.supplier_organization_id,
                       so.accepted_amount, so.delivery_fee, so.updated_at
                  from supplier_order so
                  join supplier_store ss on ss.id = so.supplier_store_id
             left join commission_calculation c on c.supplier_order_id = so.id
                 where so.status = 'COMPLETED'
                   and so.updated_at >= ? and so.updated_at < ?
                   and c.id is null
                 order by so.id
                """,
                rs -> {
                    orders.add(new SettleableOrder(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                            rs.getBigDecimal(4), rs.getBigDecimal(5),
                            rs.getTimestamp(6).toInstant()));
                },
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        return orders;
    }

    /**
     * What the payment records say was actually taken for a set of orders.
     *
     * <p>The other side of reconciliation: settlement says what a supplier is owed
     * from the order records, this says what the restaurants actually paid. Doc 09
     * §11 requires the two to be checkable against each other, and a mismatch is
     * the first sign that a capture failed silently or a refund went unaccounted.
     */
    public BigDecimal capturedFor(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        var captured = jdbc.queryForObject("""
                select coalesce(sum(captured_amount - refunded_amount), 0)
                  from payment where supplier_order_id in (%s)
                """.formatted(placeholders), BigDecimal.class, orderIds.toArray());
        return captured == null ? BigDecimal.ZERO : captured;
    }

    /**
     * One order as settlement sees it, in whatever state it is, with its status.
     * For a refund decided before the order is settled (D-104).
     */
    public record OrderFigures(SettleableOrder order, String status) {
    }

    public java.util.Optional<OrderFigures> orderFigures(Long supplierOrderId) {
        var rows = jdbc.query("""
                select so.id, so.supplier_store_id, ss.supplier_organization_id,
                       so.accepted_amount, so.delivery_fee, so.updated_at, so.status
                  from supplier_order so
                  join supplier_store ss on ss.id = so.supplier_store_id
                 where so.id = ?
                """,
                (rs, row) -> new OrderFigures(new SettleableOrder(rs.getLong(1), rs.getLong(2),
                        rs.getLong(3),
                        rs.getBigDecimal(4) == null ? BigDecimal.ZERO : rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getTimestamp(6).toInstant()), rs.getString(7)),
                supplierOrderId);
        return rows.stream().findFirst();
    }

    /**
     * Refund requests on this settlement's orders that nobody has decided yet —
     * asked for, or declined by the supplier and waiting for operations (D-104).
     * While there are any, the payout they would come out of cannot be approved.
     */
    public List<String> undecidedRefunds(Long settlementId) {
        return jdbc.queryForList("""
                select concat(so.order_number, ' (request ', dr.id, ', ', dr.status, ')')
                  from dispute_refund dr
                  join commission_calculation c on c.supplier_order_id = dr.supplier_order_id
                  join supplier_order so on so.id = dr.supplier_order_id
                 where c.settlement_id = ? and dr.status in ('REQUESTED', 'DECLINED')
                 order by dr.id
                """, String.class, settlementId);
    }

    /** Stores with unsettled completed orders. */
    public List<Long> storesWithSettleableOrders(Instant from, Instant to) {
        List<Long> stores = new ArrayList<>();
        jdbc.query("""
                select distinct so.supplier_store_id
                  from supplier_order so
             left join commission_calculation c on c.supplier_order_id = so.id
                 where so.status = 'COMPLETED'
                   and so.updated_at >= ? and so.updated_at < ?
                   and c.id is null
                """,
                rs -> {
                    stores.add(rs.getLong(1));
                },
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        return stores;
    }
}
