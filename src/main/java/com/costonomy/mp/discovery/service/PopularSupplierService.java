package com.costonomy.mp.discovery.service;

import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suppliers worth putting in front of a kitchen, with what they stock.
 *
 * <p><b>"Popular" is a placeholder, and deliberately a visible one.</b> Ranking
 * by orders placed, fill rate or repeat business is a real decision nobody has
 * made yet, and inventing one here would bury it in a SQL {@code order by} where
 * the next person would find it by accident. Until then this is the nearest
 * active suppliers who actually list something — which is at least true, and is
 * the floor any real ranking has to clear anyway.
 *
 * <p><b>The categories come from the catalogue, not from anything a supplier
 * wrote about themselves.</b> A store that calls itself "general provisions" and
 * lists nothing but dairy should read as dairy, because that is what a kitchen
 * can actually buy there.
 */
@Service
@RequiredArgsConstructor
public class PopularSupplierService {

    private final JdbcTemplate jdbc;
    private final DiscoveryDirectory directory;
    private final SupplierPerformanceProvider performance;
    private final ServiceabilityPolicy serviceabilityPolicy;

    /** How many aisles to name per supplier before the tail stops earning space. */
    private static final int CATEGORIES_PER_SUPPLIER = 6;

    @Transactional(readOnly = true)
    public List<DiscoveryDtos.PopularSupplier> forOutlet(Long outletId, int limit) {
        return forOutlet(outletId, limit, null);
    }

    /**
     * @param categoryId when set, only suppliers stocking that aisle
     *
     * <p><b>Filtered here rather than on the client.</b> Each supplier's
     * {@code categories} list is capped at six for display, so filtering that
     * list would hide a supplier who stocks the aisle but lists seven others
     * more deeply — a browse page that silently omits suppliers is worse than
     * no browse page.
     */
    @Transactional(readOnly = true)
    public List<DiscoveryDtos.PopularSupplier> forOutlet(Long outletId, int limit, Long categoryId) {
        return forOutlet(outletId, limit, categoryId, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<DiscoveryDtos.PopularSupplier> forOutlet(Long outletId, int limit, Long categoryId,
                                                         BigDecimal radiusKm, Boolean openNow,
                                                         Integer minRating, String sort) {
        String effectiveSort = SupplierListFilters.requireSort(sort);
        SupplierListFilters.requireMinRating(minRating);

        var outlet = outletId == null ? null : directory.outlet(outletId).orElse(null);

        // One row per store, with what they list. Counted from purchasable
        // offers rather than from the SKU table: a catalogue full of delisted
        // rows is not a catalogue a kitchen can order from.
        // The aisle filter is an EXISTS rather than a join condition: joining
        // would also shrink sku_count to that aisle, and the tile is supposed to
        // say how much the supplier lists altogether.
        String aisle = categoryId == null ? "" : """
                   and exists (
                       select 1
                         from supplier_offer f2
                         join supplier_sku k2 on k2.id = f2.supplier_sku_id and k2.status = 'ACTIVE'
                         join canonical_product cp2 on cp2.id = k2.canonical_product_id
                        where f2.supplier_store_id = s.id
                          and f2.status = 'ACTIVE'
                          and (f2.effective_to is null or f2.effective_to > now())
                          and cp2.category_id = ?)
                """;

        var stores = jdbc.query("""
                select s.id, o.display_name, s.name, s.city,
                       s.latitude, s.longitude, count(distinct f.id) as sku_count
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                  join supplier_offer f on f.supplier_store_id = s.id and f.status = 'ACTIVE'
                  join supplier_sku k on k.id = f.supplier_sku_id and k.status = 'ACTIVE'
                  where s.status = 'ACTIVE'
                    and o.lifecycle_status = 'ACTIVE'
                    and (f.effective_to is null or f.effective_to > now())
                %s
                 group by s.id, o.display_name, s.name, s.city, s.latitude, s.longitude
                 order by sku_count desc, s.id
                """.formatted(aisle),
                (rs, row) -> new StoreRow(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getInt(7)),
                categoryId == null ? new Object[0] : new Object[] { categoryId });

        if (stores.isEmpty()) {
            return List.of();
        }

        var storeIdsAll = stores.stream().map(StoreRow::id).toList();
        var storeInfo = directory.stores(storeIdsAll);
        // Ratings are an all-time aggregate over order history. They are needed for every store only to filter or sort
        // by rating; otherwise they are fetched for the page that is returned (D-181).
        boolean needsRatings = minRating != null || "rating".equals(effectiveSort);
        var ratingsAll = needsRatings ? performance.forStores(storeIdsAll) : null;

        // Filter by serviceability, distance radius, openNow, minRating BEFORE applying the limit.
        int clampedLimit = Math.min(Math.max(1, limit), 100);
        record SizedPop(StoreRow store, Double distanceKm, BigDecimal avgRating, int ratingCount, boolean openNow, boolean directOrdersEnabled) {
        }

        List<SizedPop> candidates = new ArrayList<>();
        for (StoreRow store : stores) {
            Double distance = outlet == null ? null : Serviceability.distanceKm(
                    store.latitude(), store.longitude(),
                    outlet.latitude(), outlet.longitude());

            if (outlet != null) {
                var info = storeInfo.get(store.id());
                if (!serviceabilityPolicy.serves(info, outlet.pincode(), distance)) {
                    continue;
                }
            }

            if (radiusKm != null && distance != null && BigDecimal.valueOf(distance).compareTo(radiusKm) > 0) {
                continue;
            }

            var info = storeInfo.get(store.id());
            boolean isOpen = info == null || info.openNow();
            if (Boolean.TRUE.equals(openNow) && !isOpen) {
                continue;
            }

            var metrics = ratingsAll == null ? null : ratingsAll.get(store.id());
            BigDecimal avgRating = metrics == null ? null : metrics.averageRating().orElse(null);
            int ratingCount = metrics == null ? 0 : metrics.ratingCount();
            if (minRating != null) {
                if (avgRating == null || avgRating.compareTo(BigDecimal.valueOf(minRating)) < 0) {
                    continue;
                }
            }

            boolean directOrders = info != null && info.directOrdersEnabled();
            candidates.add(new SizedPop(store, distance, avgRating, ratingCount, isOpen, directOrders));
        }

        if ("rating".equals(effectiveSort)) {
            candidates.sort(Comparator.comparing(
                    (SizedPop s) -> s.avgRating() == null ? BigDecimal.valueOf(-1) : s.avgRating(),
                    Comparator.reverseOrder())
                    .thenComparing(s -> s.distanceKm() == null ? Double.MAX_VALUE : s.distanceKm())
                    .thenComparing(s -> s.store().id()));
        } else {
            // Nearest first where both ends are located. A supplier with no coordinates sorts last rather than being
            // dropped: they can still be ordered from, and a directory that hides them is wrong in a way a kitchen
            // cannot see.
            candidates.sort((a, b) -> {
                if (a.distanceKm() == null && b.distanceKm() == null) return 0;
                if (a.distanceKm() == null) return 1;
                if (b.distanceKm() == null) return -1;
                return Double.compare(a.distanceKm(), b.distanceKm());
            });
        }

        List<SizedPop> ranked = candidates.stream().limit(clampedLimit).toList();
        if (ratingsAll == null) {
            var pageRatings = performance.forStores(ranked.stream().map(entry -> entry.store().id()).toList());
            ranked = ranked.stream().map(entry -> {
                var metrics = pageRatings.get(entry.store().id());
                return new SizedPop(entry.store(), entry.distanceKm(),
                        metrics == null ? null : metrics.averageRating().orElse(null),
                        metrics == null ? 0 : metrics.ratingCount(),
                        entry.openNow(), entry.directOrdersEnabled());
            }).toList();
        }

        var selectedStoreIds = ranked.stream().map(entry -> entry.store().id()).toList();
        var categories = categoriesFor(selectedStoreIds);

        var out = new ArrayList<DiscoveryDtos.PopularSupplier>(ranked.size());
        for (SizedPop entry : ranked) {
            var store = entry.store();
            out.add(new DiscoveryDtos.PopularSupplier(
                    store.id(), store.supplierName(), store.storeName(),
                    null, store.city(),
                    entry.distanceKm() == null ? null
                            : Serviceability.round(entry.distanceKm()),
                    entry.avgRating(),
                    entry.ratingCount(),
                    store.skuCount(),
                    entry.openNow(),
                    entry.directOrdersEnabled(),
                    categories.getOrDefault(store.id(), List.of())));
        }
        return out;
    }

    /** What each store stocks, deepest aisle first. */
    private Map<Long, List<DiscoveryDtos.SupplierCategory>> categoriesFor(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        String places = String.join(",", storeIds.stream().map(id -> "?").toList());

        var byStore = new LinkedHashMap<Long, List<DiscoveryDtos.SupplierCategory>>();
        jdbc.query("""
                select f.supplier_store_id, pc.id, pc.name, count(distinct f.id) as n
                  from supplier_offer f
                  join supplier_sku k on k.id = f.supplier_sku_id and k.status = 'ACTIVE'
                  join canonical_product cp on cp.id = k.canonical_product_id
                  join product_category pc on pc.id = cp.category_id
                 where f.status = 'ACTIVE'
                   and (f.effective_to is null or f.effective_to > now())
                   and f.supplier_store_id in (%s)
                 group by f.supplier_store_id, pc.id, pc.name
                 order by f.supplier_store_id, n desc, pc.name
                """.formatted(places),
                rs -> {
                    var list = byStore.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>());
                    if (list.size() < CATEGORIES_PER_SUPPLIER) {
                        list.add(new DiscoveryDtos.SupplierCategory(
                                rs.getLong(2), rs.getString(3), rs.getInt(4)));
                    }
                },
                storeIds.toArray());
        return byStore;
    }

    private record StoreRow(Long id, String supplierName, String storeName,
                            String city,
                            BigDecimal latitude, BigDecimal longitude, int skuCount) {
    }

    private record Ranked(StoreRow store, Double distanceKm) {
    }
}
