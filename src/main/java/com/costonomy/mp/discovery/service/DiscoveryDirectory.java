package com.costonomy.mp.discovery.service;

import com.costonomy.mp.catalog.repository.BrandRepository;
import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.supplier.domain.OperatingHours;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Outlet, store and brand facts that ranking needs.
 *
 * <p>Outlets and stores are common's (D-208), read through {@link Directory}; only
 * the delivery policy is Marketplace's own table, read with {@link JdbcTemplate}
 * rather than through the supplier repositories, for the same reason as
 * the access module and {@code CatalogDirectory}: discovery is a read-only
 * consumer of those domains and must not take a dependency on their internals.
 */
@Service
@RequiredArgsConstructor
public class DiscoveryDirectory {

    /** Ids per {@code in (...)} list, well under any driver's placeholder limit. */
    private static final int CHUNK = 1000;

    private final JdbcTemplate jdbc;
    private final Directory common;
    private final BrandRepository brands;

    public record OutletInfo(
            Long outletId, BigDecimal latitude, BigDecimal longitude, String pincode, String city) {
    }

    /**
     * @param tradeable            the store takes orders: ACTIVE and online, <em>and</em> its
     *                             supplier ACTIVE ({@link SupplierStanding#takesOrders}).
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
            String opensAt,
            /** Restaurants may order here without sending a request first. D-094. */
            boolean directOrdersEnabled) {
    }

    /** An ACTIVE restaurant outlet; stores and inactive outlets are not somewhere to deliver. */
    public Optional<OutletInfo> outlet(Long outletId) {
        return common.outlet(outletId)
                .filter(o -> !"STORE".equals(o.kind()) && "ACTIVE".equals(o.status()))
                .map(o -> new OutletInfo(o.id(), o.latitude(), o.longitude(), o.pincode(), o.city()));
    }

    /** These stores, by id; ids common does not know as a store are left out. */
    public Map<Long, StoreInfo> stores(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        var found = common.outlets(storeIds).values().stream()
                .filter(o -> "STORE".equals(o.kind()))
                .toList();
        Map<Long, StoreInfo> result = new HashMap<>();
        toInfo(found).forEach(info -> result.put(info.storeId(), info));
        return result;
    }

    /**
     * Every store in the Marketplace, trading or not.
     *
     * <p>Not narrowed to a point: a store's pincode list can reach an outlet
     * farther away than any radius, so who serves an outlet is decided per store
     * by {@link ServiceabilityPolicy}, not by a distance cut on the query.
     */
    public List<StoreInfo> allStores() {
        return toInfo(common.stores(null));
    }

    /**
     * The other stores of this store's supplier that take orders. D-208: was a
     * self-join on {@code supplier_store}; now the business's outlets in common.
     */
    public List<Long> tradingSiblings(Long storeId) {
        var store = common.outlet(storeId).filter(o -> "STORE".equals(o.kind())).orElse(null);
        if (store == null) {
            return List.of();
        }
        return common.outletsOf(store.orgId()).stream()
                .filter(o -> "STORE".equals(o.kind()) && o.id() != store.id())
                .filter(SupplierStanding::takesOrders)
                .map(AppOutlet::id)
                .toList();
    }

    private List<StoreInfo> toInfo(List<AppOutlet> stores) {
        if (stores.isEmpty()) {
            return List.of();
        }
        var policies = deliveryPolicies(stores.stream().map(AppOutlet::id).toList());
        var now = ZonedDateTime.now(OperatingHours.ZONE);
        List<StoreInfo> out = new ArrayList<>(stores.size());
        for (AppOutlet s : stores) {
            var policy = policies.get(s.id());
            // Absent hours are the defaults, never "closed": every store
            // predating the field would otherwise vanish from search.
            var hours = SupplierStanding.hours(s);
            out.add(new StoreInfo(
                    s.id(), s.name(), s.orgName(), SupplierStanding.takesOrders(s),
                    s.latitude(), s.longitude(), s.city(),
                    SupplierStanding.responseSlaSeconds(s), SupplierStanding.preparationMinutes(s),
                    policy == null ? null : policy.maxRadiusKm(),
                    policy == null ? Set.of() : policy.pincodes(),
                    hours.isOpenAt(now),
                    hours.opensAt().toString(),
                    SupplierStanding.directOrdersEnabled(s)));
        }
        return out;
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
                                new Policy(rs.getBigDecimal(2), parsePincodes(rs.getString(3))));
                    },
                    chunk.toArray());
        }
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
