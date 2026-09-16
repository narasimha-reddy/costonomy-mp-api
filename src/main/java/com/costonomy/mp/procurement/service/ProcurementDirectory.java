package com.costonomy.mp.procurement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.costonomy.mp.supplier.domain.OperatingHours;
import com.costonomy.mp.supplier.domain.OperatingHoursCodec;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Facts from other modules that procurement needs to read.
 *
 * <p>{@link JdbcTemplate} rather than other modules' repositories, for the same
 * reason as {@code ScopeResolver} and {@code DiscoveryDirectory}: read-only
 * projections across a module boundary, without taking a dependency on that
 * module's internals.
 */
@Service
@RequiredArgsConstructor
public class ProcurementDirectory {

    private final JdbcTemplate jdbc;

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
            String opensAt) {
    }

    public Map<Long, StoreInfo> stores(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(storeIds.size(), "?"));

        Map<Long, StoreInfo> result = new HashMap<>();
        jdbc.query("""
                select s.id, s.name, o.display_name, s.status, o.lifecycle_status,
                       s.response_sla_seconds, s.latitude, s.longitude,
                       s.operating_hours_json
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                 where s.id in (%s)
                """.formatted(placeholders),
                // A statement block, not an expression: Map.put returns a value,
                // which makes the lambda ambiguous between ResultSetExtractor and
                // RowCallbackHandler.
                rs -> {
                    // Absent hours are the defaults, never "closed".
                    var hours = OperatingHoursCodec.read(rs.getString(9));
                    boolean open = hours.isOpenAt(ZonedDateTime.now(OperatingHours.ZONE));
                    result.put(rs.getLong(1), new StoreInfo(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            "ACTIVE".equals(rs.getString(4)) && "ACTIVE".equals(rs.getString(5))
                                    && open,
                            rs.getInt(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                            open, hours.opensAt().toString()));
                },
                storeIds.toArray());
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
        var rows = jdbc.query("""
                select o.name, r.name, o.landmark, o.address_line1, o.city,
                       o.latitude, o.longitude
                  from outlet o
                  join restaurant r on r.id = o.restaurant_id
                 where o.id = ?
                """,
                (rs, i) -> new OutletSummary(
                        rs.getString(1), rs.getString(2),
                        rs.getString(3) != null ? rs.getString(3) : rs.getString(4),
                        rs.getString(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7)),
                outletId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long restaurantIdOfOutlet(Long outletId) {
        var ids = jdbc.queryForList(
                "select restaurant_id from outlet where id = ?", Long.class, outletId);
        return ids.isEmpty() ? null : ids.get(0);
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

    /**
     * The requester's role at this outlet, for approval policies keyed on role.
     *
     * <p>A user can hold several roles; the highest-privilege one is returned,
     * ordered by the hierarchy the seed defines. A policy saying "orders raised by
     * Procurement Staff need approval" must not be dodged by that person also
     * holding a Store Manager grant — so the strongest role is what the policy sees.
     */
    public String primaryRoleOf(Long userId, Long outletId) {
        var roles = jdbc.queryForList("""
                select r.code
                  from user_role ur
                  join role r on r.id = ur.role_id
                 where ur.user_id = ?
                   and ur.status = 'ACTIVE'
                   and (
                        (ur.scope_type = 'OUTLET' and ur.scope_id = ?)
                     or (ur.scope_type = 'RESTAURANT'
                         and ur.scope_id = (select restaurant_id from outlet where id = ?))
                   )
                """, String.class, userId, outletId, outletId);

        List<String> precedence = List.of(
                "REST_OWNER", "REST_ADMIN", "REST_PURCHASE_MANAGER", "REST_STORE_MANAGER",
                "REST_FINANCE_STAFF", "REST_RECEIVING_STAFF", "REST_PROCUREMENT_STAFF");

        return precedence.stream().filter(roles::contains).findFirst()
                .orElse(roles.isEmpty() ? null : roles.get(0));
    }
}
