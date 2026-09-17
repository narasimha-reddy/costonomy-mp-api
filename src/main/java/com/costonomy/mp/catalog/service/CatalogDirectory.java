package com.costonomy.mp.catalog.service;

import com.costonomy.mp.catalog.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final BrandRepository brands;

    /**
     * Display and ranking facts about a supplier store.
     *
     * @param active whether the store can currently receive orders (doc 03 §15) —
     *               the store ACTIVE <em>and</em> its organisation ACTIVE, so a
     *               shop under a supplier still awaiting verification is not
     * @param serviceablePincodes an explicit allow-list; empty means geography decides
     */
    public record StoreInfo(
            String storeName,
            String supplierName,
            boolean active,
            Integer responseSlaSeconds,
            Integer preparationMinutes,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal maxDeliveryRadiusKm,
            Set<String> serviceablePincodes) {
    }

    /** Where an outlet is, for deciding which stores can reach it. */
    public record OutletInfo(BigDecimal latitude, BigDecimal longitude, String pincode) {
    }

    public Optional<OutletInfo> outlet(Long outletId) {
        if (outletId == null) {
            return Optional.empty();
        }
        var rows = jdbc.query(
                "select latitude, longitude, pincode from outlet where id = ?",
                (rs, n) -> new OutletInfo(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)),
                outletId);
        return rows.stream().findFirst();
    }

    public Map<Long, StoreInfo> storeInfo(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(storeIds.size(), "?"));

        Map<Long, StoreInfo> result = new HashMap<>();
        jdbc.query("""
                select s.id, s.name, o.display_name, s.status, o.lifecycle_status,
                       s.response_sla_seconds, s.preparation_minutes,
                       s.latitude, s.longitude,
                       d.max_delivery_radius_km, d.serviceable_pincodes_json
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                  left join supplier_delivery_policy d on d.supplier_store_id = s.id
                 where s.id in (%s)
                """.formatted(placeholders),
                rs -> {
                    // Both must be true: an ACTIVE store under an organisation that
                    // is suspended, or still awaiting verification, cannot trade.
                    boolean active = "ACTIVE".equals(rs.getString(4))
                            && "ACTIVE".equals(rs.getString(5));
                    result.put(rs.getLong(1), new StoreInfo(
                            rs.getString(2), rs.getString(3), active,
                            rs.getInt(6), rs.getInt(7),
                            rs.getBigDecimal(8), rs.getBigDecimal(9),
                            rs.getBigDecimal(10), pincodes(rs.getString(11))));
                },
                storeIds.toArray());
        return result;
    }

    /** A JSON array of pincodes, or empty when the store named none. */
    private Set<String> pincodes(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return new HashSet<>(MAPPER.readValue(json, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException ex) {
            // Unreadable is not "serves everywhere" and not "serves nowhere" — it
            // is no list, which hands the decision back to geography.
            return Set.of();
        }
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
