package com.costonomy.mp.discovery.service;

import com.costonomy.mp.catalog.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.costonomy.mp.supplier.domain.OperatingHours;
import com.costonomy.mp.supplier.domain.OperatingHoursCodec;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Outlet, store and brand facts that ranking needs.
 *
 * <p>Reads across module boundaries with {@link JdbcTemplate} rather than through
 * the restaurant and supplier repositories, for the same reason as
 * {@code ScopeResolver} and {@code CatalogDirectory}: discovery is a read-only
 * consumer of those domains and must not take a dependency on their internals.
 */
@Service
@RequiredArgsConstructor
public class DiscoveryDirectory {

    private final JdbcTemplate jdbc;
    private final BrandRepository brands;

    public record OutletInfo(
            Long outletId, BigDecimal latitude, BigDecimal longitude, String pincode, String city) {
    }

    /**
     * @param tradeable            store is ACTIVE <em>and</em> its organisation is ACTIVE.
     *                             Both, because an open store under a suspended
     *                             supplier still cannot accept an order (doc 03 §15).
     * @param serviceablePincodes  an explicit allow-list; empty means geography decides
     */
    public record StoreInfo(
            Long storeId,
            String storeName,
            String supplierName,
            boolean tradeable,
            BigDecimal latitude,
            BigDecimal longitude,
            String city,
            Integer responseSlaSeconds,
            Integer preparationMinutes,
            BigDecimal maxDeliveryRadiusKm,
            Set<String> serviceablePincodes,
            /**
             * Whether the store is trading right now.
             * <p>Separate from {@code tradeable}, which is about the store and the
             * organisation being active at all. A store can be perfectly active
             * and simply shut at 11pm, and a restaurant should be told which.
             */
            boolean openNow,
            /** {@code HH:mm} the store next opens, for the "opens at" label. */
            String opensAt) {
    }

    public Optional<OutletInfo> outlet(Long outletId) {
        var rows = jdbc.query("""
                select id, latitude, longitude, pincode, city
                  from outlet where id = ? and status = 'ACTIVE'
                """,
                (rs, i) -> new OutletInfo(rs.getLong(1),
                        rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getString(5)),
                outletId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<Long, StoreInfo> stores(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(storeIds.size(), "?"));

        Map<Long, StoreInfo> result = new HashMap<>();
        jdbc.query("""
                select s.id, s.name, o.display_name, s.status, o.lifecycle_status,
                       s.latitude, s.longitude, s.city,
                       s.response_sla_seconds, s.preparation_minutes,
                       d.max_delivery_radius_km, d.serviceable_pincodes_json,
                       s.operating_hours_json
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                  left join supplier_delivery_policy d on d.supplier_store_id = s.id
                 where s.id in (%s)
                """.formatted(placeholders),
                rs -> {
                    boolean tradeable = "ACTIVE".equals(rs.getString(4))
                            && "ACTIVE".equals(rs.getString(5));
                    // Absent hours are the defaults, never "closed": every store
                    // predating the field would otherwise vanish from search.
                    var hours = OperatingHoursCodec.read(rs.getString(13));
                    result.put(rs.getLong(1), new StoreInfo(
                            rs.getLong(1), rs.getString(2), rs.getString(3), tradeable,
                            rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8),
                            rs.getInt(9), rs.getInt(10),
                            rs.getBigDecimal(11), parsePincodes(rs.getString(12)),
                            hours.isOpenAt(ZonedDateTime.now(OperatingHours.ZONE)),
                            hours.opensAt().toString()));
                },
                storeIds.toArray());
        return result;
    }

    public Map<Long, String> brandNames(List<Long> brandIds) {
        if (brandIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        brands.findAllById(brandIds).forEach(brand -> names.put(brand.getId(), brand.getName()));
        return names;
    }

    /**
     * Parse the pincode allow-list.
     *
     * <p>Malformed JSON yields an empty set, which means geography decides. That
     * is the safe failure: excluding the store entirely would make a supplier
     * invisible because of a bad config value, and including everything would send
     * them orders they cannot serve — the radius check still applies.
     */
    private static Set<String> parsePincodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> pincodes = new HashSet<>();
        for (String part : raw.replaceAll("[\\[\\]\"\\s]", "").split(",")) {
            if (!part.isEmpty()) {
                pincodes.add(part);
            }
        }
        return pincodes;
    }
}
