package com.costonomy.mp.catalog.service;

import com.costonomy.mp.catalog.repository.BrandRepository;
import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Supplier and brand names for catalog responses.
 *
 * <p>Stores, their suppliers and outlets are common's (D-208), read through
 * {@link Directory}. The delivery policy is read with {@link JdbcTemplate} rather
 * than through the supplier module's repositories, for the same reason as
 * the access module: a module reaching into another module's repositories is
 * the coupling the modular-monolith rules exist to prevent. This is a read-only
 * projection for display, not a claim on the supplier domain.
 */
@Service
@RequiredArgsConstructor
public class CatalogDirectory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ids per {@code in (...)} list. */
    private static final int CHUNK = 1000;

    private final JdbcTemplate jdbc;
    private final Directory common;
    private final BrandRepository brands;

    /**
     * Display and ranking facts about a supplier store.
     *
     * @param active whether the store can currently receive orders (doc 03 §15) —
     *               {@link SupplierStanding#takesOrders}: the store ACTIVE and online
     *               <em>and</em> its supplier ACTIVE, so a shop under a supplier
     *               still awaiting verification is not
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
        return common.outlet(outletId)
                .filter(o -> !"STORE".equals(o.kind()))
                .map(o -> new OutletInfo(o.latitude(), o.longitude(), o.pincode()));
    }

    public Map<Long, StoreInfo> storeInfo(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        var stores = common.outlets(storeIds).values().stream()
                .filter(o -> "STORE".equals(o.kind()))
                .toList();
        var policies = deliveryPolicies(stores.stream().map(AppOutlet::id).toList());

        Map<Long, StoreInfo> result = new HashMap<>();
        for (AppOutlet s : stores) {
            var policy = policies.get(s.id());
            result.put(s.id(), new StoreInfo(
                    s.name(), s.orgName(), SupplierStanding.takesOrders(s),
                    SupplierStanding.responseSlaSeconds(s), SupplierStanding.preparationMinutes(s),
                    s.latitude(), s.longitude(),
                    policy == null ? null : policy.maxRadiusKm(),
                    policy == null ? Set.of() : policy.pincodes()));
        }
        return result;
    }

    private record Policy(BigDecimal maxRadiusKm, Set<String> pincodes) {
    }

    /** Each store's delivery policy, Marketplace's own table. */
    private Map<Long, Policy> deliveryPolicies(List<Long> storeIds) {
        Map<Long, Policy> result = new HashMap<>();
        for (int from = 0; from < storeIds.size(); from += CHUNK) {
            var chunk = storeIds.subList(from, Math.min(from + CHUNK, storeIds.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.query("""
                    select supplier_store_id, max_delivery_radius_km, serviceable_pincodes_json
                      from supplier_delivery_policy
                     where supplier_store_id in (%s)
                    """.formatted(placeholders),
                    rs -> {
                        result.put(rs.getLong(1),
                                new Policy(rs.getBigDecimal(2), pincodes(rs.getString(3))));
                    },
                    chunk.toArray());
        }
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
