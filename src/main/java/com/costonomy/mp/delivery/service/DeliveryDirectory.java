package com.costonomy.mp.delivery.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.costonomy.mp.delivery.domain.ConsignmentWeight;

import java.math.BigDecimal;
import java.util.List;

/**
 * Facts from other modules that delivery needs to read.
 *
 * <p>{@link JdbcTemplate} across the module edge, like {@code ProcurementDirectory}
 * and {@code CreditDirectory}.
 */
@Service
@RequiredArgsConstructor
public class DeliveryDirectory {

    private final JdbcTemplate jdbc;

    public record OrderInfo(
            Long orderId,
            String orderNumber,
            String status,
            Long outletId,
            Long supplierStoreId,
            BigDecimal deliveryFee,
            String deliveryMode) {
    }

    public OrderInfo order(Long supplierOrderId) {
        var rows = jdbc.query("""
                select id, order_number, status, outlet_id, supplier_store_id,
                       delivery_fee, delivery_mode
                  from supplier_order where id = ?
                """,
                (rs, row) -> new OrderInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getBigDecimal(6), rs.getString(7)),
                supplierOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A pickup or drop point, with someone to call when the driver cannot find it. */
    public record Place(
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactName,
            String contactPhone) {
    }

    public Place pickupFor(Long supplierStoreId) {
        var rows = jdbc.query("""
                select s.name, concat_ws(', ', s.address_line1, s.address_line2, s.city,
                       s.state, s.pincode), s.latitude, s.longitude,
                       s.contact_name, s.contact_phone
                  from supplier_store s where s.id = ?
                """,
                (rs, row) -> new Place(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6)),
                supplierStoreId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Place dropFor(Long outletId) {
        var rows = jdbc.query("""
                select o.name, concat_ws(', ', o.address_line1, o.address_line2, o.city,
                       o.state, o.pincode), o.latitude, o.longitude,
                       o.contact_name, o.contact_phone
                  from outlet o where o.id = ?
                """,
                (rs, row) -> new Place(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6)),
                outletId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * A store's delivery settings. Doc 01 §20.
     *
     * <p>A store with no row has not configured delivery. Costonomy delivery is
     * the default there — the supplier has not said they will carry it themselves,
     * and assuming they will would leave an order nobody collects.
     */
    public record DeliveryPolicy(
            boolean ownDeliveryEnabled,
            boolean costonomyDeliveryEnabled,
            BigDecimal ownDeliveryFee,
            BigDecimal ownDeliveryMinOrderValue,
            BigDecimal maxDeliveryRadiusKm) {

        public static final DeliveryPolicy DEFAULT = new DeliveryPolicy(
                false, true, BigDecimal.ZERO, null, null);
    }

    public DeliveryPolicy deliveryPolicy(Long supplierStoreId) {
        var rows = jdbc.query("""
                select own_delivery_enabled, costonomy_delivery_enabled, own_delivery_fee,
                       own_delivery_min_order_value, max_delivery_radius_km
                  from supplier_delivery_policy where supplier_store_id = ?
                """,
                (rs, row) -> new DeliveryPolicy(rs.getBoolean(1), rs.getBoolean(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)),
                supplierStoreId);
        return rows.isEmpty() ? DeliveryPolicy.DEFAULT : rows.get(0);
    }

    // ── What is being carried ───────────────────────────────────────────

    /**
     * The lines of a request, as weighable rows. D-091.
     *
     * <p>Quantities are the requested ones: a quote is taken before the order
     * exists, so there is nothing else to weigh — and the restaurant orders what
     * the supplier offered, which is at most this.
     */
    public List<ConsignmentWeight.Line> intentLines(Long intentId) {
        return jdbc.query("""
                select i.requested_quantity, s.weight_grams, s.pack_size, s.pack_unit,
                       s.measure_value, s.measure_unit
                  from intent_item i
                  join supplier_sku s on s.id = i.supplier_sku_id
                 where i.intent_id = ?
                """,
                (rs, row) -> new ConsignmentWeight.Line(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6)),
                intentId);
    }

    /**
     * What an order's accepted quantities weigh.
     *
     * <p>Accepted, not requested: by booking time the supplier has committed, and
     * a courier is carrying what is in the van.
     */
    public BigDecimal consignmentWeightGrams(Long supplierOrderId) {
        var lines = jdbc.query("""
                select coalesce(i.accepted_quantity, i.requested_quantity),
                       s.weight_grams, s.pack_size, s.pack_unit,
                       s.measure_value, s.measure_unit
                  from supplier_order_item i
                  join supplier_sku s on s.id = i.supplier_sku_id
                 where i.supplier_order_id = ?
                """,
                (rs, row) -> new ConsignmentWeight.Line(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6)),
                supplierOrderId);
        return ConsignmentWeight.of(lines, DEFAULT_PIECE_GRAMS).grams();
    }

    /**
     * What to assume a countable pack weighs when nothing says.
     *
     * <p>Half a kilo: heavy enough that a large order of them does not quote as a
     * bike run, light enough not to price every crate as a truck. The real fix is
     * {@code supplier_sku.weight_grams}, not a better constant.
     */
    public static final BigDecimal DEFAULT_PIECE_GRAMS = BigDecimal.valueOf(500);
}
