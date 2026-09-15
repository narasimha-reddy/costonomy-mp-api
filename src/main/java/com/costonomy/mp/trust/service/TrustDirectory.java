package com.costonomy.mp.trust.service;

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
            Long supplierStoreId) {
    }

    public OrderInfo order(Long supplierOrderId) {
        var rows = jdbc.query("""
                select id, order_number, status, outlet_id, supplier_store_id
                  from supplier_order where id = ?
                """,
                (rs, row) -> new OrderInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5)),
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
            String unit) {
    }

    public List<OrderLine> linesOf(Long supplierOrderId) {
        List<OrderLine> lines = new ArrayList<>();
        jdbc.query("""
                select i.id, p.name, i.requested_quantity, i.accepted_quantity, i.unit
                  from supplier_order_item i
                  join canonical_product p on p.id = i.canonical_product_id
                 where i.supplier_order_id = ? order by i.id
                """,
                rs -> {
                    lines.add(new OrderLine(rs.getLong(1), rs.getString(2),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5)));
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

    /** Move the order to COMPLETED, guarded on it still being DELIVERED. */
    public boolean completeOrder(Long supplierOrderId) {
        return jdbc.update("""
                update supplier_order
                   set status = 'COMPLETED', version = version + 1, updated_at = now(6)
                 where id = ? and status = 'DELIVERED'
                """, supplierOrderId) == 1;
    }
}
