package com.costonomy.mp.delivery.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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
}
