package com.costonomy.mp.credit.service;

import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
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
 * the access module. Read-only projections across a module edge, without
 * taking a dependency on that module's internals. Outlets, stores and their
 * businesses come from costonomy-common-api through {@link Directory} (D-208).
 */
@Service
@RequiredArgsConstructor
public class CreditDirectory {

    private final JdbcTemplate jdbc;
    private final Directory directory;

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
        return directory.outlet(supplierStoreId).filter(s -> "STORE".equals(s.kind()))
                .map(s -> new StoreInfo(s.id(), s.name(), s.orgId(), s.orgName(),
                        SupplierStanding.takesOrders(s), s.latitude(), s.longitude()))
                .orElse(null);
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
        return outlets(outletId == null ? List.of() : List.of(outletId)).get(outletId);
    }

    /** Several outlets at once (outlet and restaurant names); an id that does not exist is simply absent. */
    public java.util.Map<Long, OutletInfo> outlets(java.util.Collection<Long> outletIds) {
        var found = new java.util.HashMap<Long, OutletInfo>();
        if (outletIds.isEmpty()) {
            return found;
        }
        directory.outlets(outletIds).values().stream()
                .filter(o -> "OUTLET".equals(o.kind()))
                .forEach(o -> found.put(o.id(), outletInfo(o)));
        return found;
    }

    private static OutletInfo outletInfo(AppOutlet o) {
        return new OutletInfo(o.id(), o.name(), o.orgId(), o.orgName(),
                o.landmark() != null ? o.landmark() : o.addressLine1(),
                o.city(), o.latitude(), o.longitude());
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
