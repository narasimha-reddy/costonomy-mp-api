package com.costonomy.mp.catalog.service;

import com.costonomy.mp.catalog.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supplier and brand names for catalog responses.
 *
 * <p>Store and organisation names are read with {@link JdbcTemplate} rather than
 * through the supplier module's repositories, for the same reason as
 * {@code ScopeResolver}: a module reaching into another module's repositories is
 * the coupling the modular-monolith rules exist to prevent. This is a read-only
 * projection for display, not a claim on the supplier domain.
 */
@Service
@RequiredArgsConstructor
public class CatalogDirectory {

    private final JdbcTemplate jdbc;
    private final BrandRepository brands;

    /**
     * Display and ranking facts about a supplier store.
     *
     * @param active whether the store can currently receive orders (doc 03 §15)
     */
    public record StoreInfo(
            String storeName,
            String supplierName,
            boolean active,
            Integer responseSlaSeconds,
            Integer preparationMinutes) {
    }

    public Map<Long, StoreInfo> storeInfo(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(storeIds.size(), "?"));

        Map<Long, StoreInfo> result = new HashMap<>();
        jdbc.query("""
                select s.id, s.name, o.display_name, s.status, o.lifecycle_status,
                       s.response_sla_seconds, s.preparation_minutes
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                 where s.id in (%s)
                """.formatted(placeholders),
                rs -> {
                    // Both must be true: an ACTIVE store under a suspended
                    // organisation cannot trade either.
                    boolean active = "ACTIVE".equals(rs.getString(4))
                            && "ACTIVE".equals(rs.getString(5));
                    result.put(rs.getLong(1), new StoreInfo(
                            rs.getString(2), rs.getString(3), active,
                            rs.getInt(6), rs.getInt(7)));
                },
                storeIds.toArray());
        return result;
    }

    public Map<Long, String> brandNames(List<Long> brandIds) {
        if (brandIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        brands.findAllById(brandIds).forEach(b -> names.put(b.getId(), b.getName()));
        return names;
    }
}
