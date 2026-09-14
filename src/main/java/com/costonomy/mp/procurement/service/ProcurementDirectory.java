package com.costonomy.mp.procurement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
     * @param tradeable store ACTIVE <em>and</em> organisation ACTIVE — doc 03 §15
     *                  only permits an order against a store that can accept one
     */
    public record StoreInfo(
            Long storeId,
            String storeName,
            String supplierName,
            boolean tradeable,
            Integer responseSlaSeconds) {
    }

    public Map<Long, StoreInfo> stores(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(storeIds.size(), "?"));

        Map<Long, StoreInfo> result = new HashMap<>();
        jdbc.query("""
                select s.id, s.name, o.display_name, s.status, o.lifecycle_status,
                       s.response_sla_seconds
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                 where s.id in (%s)
                """.formatted(placeholders),
                // A statement block, not an expression: Map.put returns a value,
                // which makes the lambda ambiguous between ResultSetExtractor and
                // RowCallbackHandler.
                rs -> {
                    result.put(rs.getLong(1), new StoreInfo(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            "ACTIVE".equals(rs.getString(4)) && "ACTIVE".equals(rs.getString(5)),
                            rs.getInt(6)));
                },
                storeIds.toArray());
        return result;
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
