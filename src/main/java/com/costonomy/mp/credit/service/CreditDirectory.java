package com.costonomy.mp.credit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Facts from other modules that credit needs to read.
 *
 * <p>{@link JdbcTemplate} rather than another module's repositories — the same
 * boundary rule as {@code ProcurementDirectory}, {@code CatalogDirectory} and
 * {@code ScopeResolver}. Read-only projections across a module edge, without
 * taking a dependency on that module's internals.
 */
@Service
@RequiredArgsConstructor
public class CreditDirectory {

    private final JdbcTemplate jdbc;

    public record OrderInfo(
            Long orderId,
            String orderNumber,
            Long outletId,
            Long supplierStoreId,
            Long procurementId,
            BigDecimal totalAmount,
            String paymentMethod) {
    }

    public OrderInfo order(Long supplierOrderId) {
        var rows = jdbc.query("""
                select id, order_number, outlet_id, supplier_store_id, procurement_id,
                       total_amount, payment_method
                  from supplier_order where id = ?
                """,
                (rs, row) -> new OrderInfo(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getBigDecimal(6), rs.getString(7)),
                supplierOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record StoreInfo(
            Long storeId,
            String storeName,
            Long supplierOrganizationId,
            String supplierName,
            boolean tradeable,
            /** The store's position, so a caller can measure the leg to an outlet. */
            BigDecimal latitude,
            BigDecimal longitude) {
    }

    public StoreInfo store(Long supplierStoreId) {
        var rows = jdbc.query("""
                select s.id, s.name, o.id, o.display_name, s.status, o.lifecycle_status,
                       s.latitude, s.longitude
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                 where s.id = ?
                """,
                (rs, row) -> new StoreInfo(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4),
                        "ACTIVE".equals(rs.getString(5)) && "ACTIVE".equals(rs.getString(6)),
                        rs.getBigDecimal(7), rs.getBigDecimal(8)),
                supplierStoreId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * @param locality the landmark where one was given, else the street line —
     *                 the same shape an incoming order carries, so a supplier
     *                 reads "who and where" identically wherever a restaurant
     *                 appears
     */
    public record OutletInfo(
            Long outletId,
            String outletName,
            Long restaurantId,
            String restaurantName,
            String locality,
            String city,
            BigDecimal latitude,
            BigDecimal longitude) {
    }

    public OutletInfo outlet(Long outletId) {
        var rows = jdbc.query("""
                select o.id, o.name, r.id, r.name, o.landmark, o.address_line1, o.city,
                       o.latitude, o.longitude
                  from outlet o join restaurant r on r.id = o.restaurant_id
                 where o.id = ?
                """,
                (rs, row) -> new OutletInfo(rs.getLong(1), rs.getString(2),
                        rs.getLong(3), rs.getString(4),
                        rs.getString(5) != null ? rs.getString(5) : rs.getString(6),
                        rs.getString(7),
                        rs.getBigDecimal(8), rs.getBigDecimal(9)),
                outletId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * A store's default credit terms. Doc 01 §18.
     *
     * <p>Defaults only — they seed what a supplier is offered when they respond to
     * a request. The agreement's own terms are what an order is checked against,
     * because a supplier may well have agreed something different with this
     * particular restaurant.
     */
    public record CreditPolicy(
            boolean creditEnabled,
            BigDecimal defaultLimit,
            Integer defaultPeriodDays,
            Integer defaultGraceDays,
            BigDecimal maxSingleOrderCredit,
            BigDecimal maxOverdueAmount,
            boolean autoSuspendEnabled) {

        public static final CreditPolicy DISABLED = new CreditPolicy(
                false, null, null, 0, null, null, true);
    }

    public CreditPolicy creditPolicy(Long supplierStoreId) {
        List<CreditPolicy> rows = jdbc.query("""
                select credit_enabled, default_credit_limit, default_credit_period_days,
                       default_grace_period_days, max_single_order_credit,
                       max_overdue_amount, auto_suspend_enabled
                  from supplier_credit_policy where supplier_store_id = ?
                """,
                (rs, row) -> new CreditPolicy(
                        rs.getBoolean(1), rs.getBigDecimal(2),
                        (Integer) rs.getObject(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBoolean(7)),
                supplierStoreId);
        // A store that has never configured credit has not opted into it. Absent
        // is not the same as enabled-with-defaults.
        return rows.isEmpty() ? CreditPolicy.DISABLED : rows.get(0);
    }
}
