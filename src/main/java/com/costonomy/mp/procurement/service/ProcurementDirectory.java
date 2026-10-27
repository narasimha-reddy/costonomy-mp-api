package com.costonomy.mp.procurement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.supplier.domain.OperatingHours;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Facts from other modules that procurement needs to read.
 *
 * <p>{@link JdbcTemplate} rather than other modules' repositories, for the same
 * reason as the access module and {@code DiscoveryDirectory}: read-only
 * projections across a module boundary, without taking a dependency on that
 * module's internals. Stores, outlets and businesses come from
 * costonomy-common-api through {@link Directory} (D-208).
 */
@Service
@RequiredArgsConstructor
public class ProcurementDirectory {

    private final JdbcTemplate jdbc;
    private final Directory directory;

    /**
     * @param tradeable store ACTIVE, organisation ACTIVE <em>and</em> the store
     *                  open right now — doc 03 §15 only permits an order against a
     *                  store that can accept one, and a shut store cannot. An order
     *                  placed at midnight would otherwise count down against a
     *                  window nobody was ever going to answer, and the restaurant
     *                  would wait the full thirty minutes to learn what was already
     *                  knowable when they tapped.
     * @param openNow   why {@code tradeable} is false, when that is the reason
     */
    public record StoreInfo(
            Long storeId,
            String storeName,
            String supplierName,
            boolean tradeable,
            Integer responseSlaSeconds,
            /** The store's own position, so a caller can measure the leg to an outlet. */
            BigDecimal latitude,
            BigDecimal longitude,
            boolean openNow,
            String opensAt,
            /**
             * This store takes orders without being asked first.
             *
             * <p>The request round trip is how we find out whether the goods are
             * there; a store holding stock has already answered that.
             */
            boolean directOrdersEnabled,
            BigDecimal minOrderValue,
            BigDecimal freeDeliveryThreshold) {
    }

    public Map<Long, StoreInfo> stores(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        // Stores from common; the delivery policy is Marketplace's own table.
        Map<Long, AppOutlet> stores = new LinkedHashMap<>(directory.outlets(storeIds));
        stores.values().removeIf(s -> !"STORE".equals(s.kind()));
        if (stores.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal[]> policies = new HashMap<>();
        String placeholders = String.join(",", Collections.nCopies(stores.size(), "?"));
        jdbc.query("""
                select supplier_store_id, min_order_value, free_delivery_threshold
                  from supplier_delivery_policy
                 where supplier_store_id in (%s)
                """.formatted(placeholders),
                // A statement block, not an expression: Map.put returns a value,
                // which makes the lambda ambiguous between ResultSetExtractor and
                // RowCallbackHandler.
                rs -> {
                    policies.put(rs.getLong(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)});
                },
                stores.keySet().toArray());

        Map<Long, StoreInfo> result = new HashMap<>();
        ZonedDateTime now = ZonedDateTime.now(OperatingHours.ZONE);
        stores.values().forEach(s -> {
            // Absent hours are the defaults, never "closed".
            var hours = SupplierStanding.hours(s);
            boolean open = hours.isOpenAt(now);
            BigDecimal[] policy = policies.getOrDefault(s.id(), new BigDecimal[2]);
            result.put(s.id(), new StoreInfo(
                    s.id(), s.name(), s.orgName(),
                    SupplierStanding.takesOrders(s) && open,
                    SupplierStanding.responseSlaSeconds(s), s.latitude(), s.longitude(),
                    open, hours.opensAt().toString(), SupplierStanding.directOrdersEnabled(s),
                    policy[0] != null ? policy[0] : BigDecimal.ZERO,
                    policy[1]));
        });
        return result;
    }

    /**
     * @param locality the landmark where one was given, else the street line —
     *                 the supplier is dispatching a van, and "Indiranagar Main
     *                 Road" answers that question where an outlet's own name,
     *                 which is whatever the restaurant chose to call it, may not
     */
    public record OutletSummary(
            String outletName,
            String restaurantName,
            String locality,
            String city,
            BigDecimal latitude,
            BigDecimal longitude) {
    }

    /** Names and whereabouts for the supplier's view of an incoming order (doc 05 §25). */
    public OutletSummary outletSummary(Long outletId) {
        return directory.outlet(outletId).filter(o -> "OUTLET".equals(o.kind()))
                .map(o -> new OutletSummary(
                        o.name(), o.orgName(),
                        o.landmark() != null ? o.landmark() : o.addressLine1(),
                        o.city(), o.latitude(), o.longitude()))
                .orElse(null);
    }

    /**
     * The earliest unsettled due date of the order's credit invoices, or null (not on credit, or none open). Plain
     * JDBC on purpose: it only words a notification, and a read that goes through the credit module's transactional
     * service would mark the releasing transaction rollback-only if it threw, even when the caller catches it.
     */
    public java.time.LocalDate creditDueDateOf(Long supplierOrderId) {
        var dates = jdbc.queryForList("""
                select min(due_date) from credit_invoice
                 where supplier_order_id = ? and status not in ('PAID', 'WRITTEN_OFF')
                """, java.sql.Date.class, supplierOrderId);
        return dates.isEmpty() || dates.get(0) == null ? null : dates.get(0).toLocalDate();
    }

    /** A published rating of an order: stars and comment. */
    public record OrderRating(int overall, String comment) {
    }

    /** The order's rating when the restaurant left one and moderation has not hidden it; null otherwise. */
    public OrderRating publishedRatingOf(Long supplierOrderId) {
        var rows = jdbc.query("""
                select overall_rating, comment from rating
                 where supplier_order_id = ? and moderation_status = 'PUBLISHED'
                """,
                (rs, i) -> new OrderRating(rs.getInt(1), rs.getString(2)), supplierOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long restaurantIdOfOutlet(Long outletId) {
        return directory.outlet(outletId).filter(o -> "OUTLET".equals(o.kind()))
                .map(AppOutlet::orgId).orElse(null);
    }

    public List<Long> categoryIdsOfProducts(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        return jdbc.queryForList("""
                select distinct category_id from canonical_product
                 where id in (%s) and category_id is not null
                """.formatted(placeholders), Long.class, productIds.toArray());
    }
}
