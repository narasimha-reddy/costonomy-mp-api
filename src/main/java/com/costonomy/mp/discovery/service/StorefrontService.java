package com.costonomy.mp.discovery.service;

import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a restaurant can actually buy, and from whom.
 *
 * <p>Three questions with one answer shape. "Which SKUs match paneer", "what does
 * this store stock" and "who is near me" are the same rows sliced differently, so
 * they share {@link DiscoveryDtos.StorefrontSku} rather than growing three
 * near-identical DTOs that drift.
 *
 * <p><b>Distance orders, it does not exclude.</b> Whether a store serves an outlet
 * is the store's own declared radius or pincode list — see
 * {@code RecommendationService.servesOutlet} — and a fixed kilometre cut applied
 * on top of that would hide a supplier who has said, in their own settings, that
 * they deliver there. So serviceability decides membership and distance decides
 * order. A caller wanting a tighter list passes {@code radiusKm}, and the count of
 * what that excluded comes back with it so the app can offer to widen.
 *
 * <p>This is deliberately not tenant-scoped. It returns the catalog a restaurant
 * is meant to shop — the same rows {@code /products/{id}/offers} already serves to
 * any authenticated user — and nothing about a supplier's own operations.
 */
@Service
@RequiredArgsConstructor
public class StorefrontService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MIN_TERM = 2;

    /**
     * How many purchasable things matching the term each store stocks.
     *
     * <p>Takes three bound parameters, all the same term, then the store ids.
     *
     * <p>Three conditions, and each rules out a supplier who cannot actually sell
     * it: the SKU is live, the offer is live and unexpired — a listing with the
     * price withdrawn is not a price — and it is <b>in stock</b>.
     *
     * <p>The availability check is the one that is easy to leave out and the one
     * that shows. "1 matching item" beside a supplier is a promise you can buy it
     * from them; against an out-of-stock offer it sends a restaurant into a
     * catalog to find a greyed-out Add button. The pack list still shows that row,
     * marked out of stock, because "they carry it and are out today" is worth
     * knowing — it is only worthless as a reason to pick the supplier.
     */
    private static final String STOCKS_MATCHING = """
            select k.supplier_store_id, count(*)
              from supplier_sku k
              join canonical_product cp on cp.id = k.canonical_product_id
              join supplier_offer f on f.supplier_sku_id = k.id
                   and f.status = 'ACTIVE'
                   and f.availability = 'AVAILABLE'
                   and (f.effective_to is null or f.effective_to > now())
              left join brand b on b.id = k.brand_id
             where k.status = 'ACTIVE'
               and (lower(k.name) like concat('%', ?, '%')
                    or lower(cp.name) like concat('%', ?, '%')
                    or lower(b.name) like concat('%', ?, '%'))
               and k.supplier_store_id in (%s)
             group by k.supplier_store_id""";

    /** Ids per {@code in (...)} list. */
    private static final int CHUNK = 1000;

    private final JdbcTemplate jdbc;
    private final DiscoveryDirectory directory;
    private final SupplierPerformanceProvider performance;
    private final ServiceabilityPolicy serviceabilityPolicy;

    /**
     * SKUs matching a term, across every supplier that serves this outlet.
     *
     * <p>Searches the supplier's own SKU name, the brand and the canonical product
     * it maps to, because a cook types what they call the thing — "curd" finds a
     * SKU named "Thick Curd Pouch" and one named "Dahi 500g" mapped to the same
     * canonical product.
     */
    @Transactional(readOnly = true)
    public List<DiscoveryDtos.StorefrontSku> searchSkus(String query, Long outletId, Integer limit) {
        String term = query == null ? "" : query.trim().toLowerCase();
        if (term.length() < MIN_TERM) {
            return List.of();
        }

        var stores = trading(directory.allStores());
        var rows = fetchSkuRows("""
                   and (lower(k.name) like concat('%', ?, '%')
                        or lower(cp.name) like concat('%', ?, '%')
                        or lower(b.name) like concat('%', ?, '%'))
                """, List.of(term, term, term), stores, limit);

        return decorate(rows, stores, outletId, null).skus();
    }

    /**
     * Everything one store currently sells.
     *
     * <p>The restaurant-facing view of a supplier's shelf, reached by tapping a
     * supplier in search. Unlike the SKU search it is not filtered by the outlet's
     * serviceability: the restaurant asked for this store by name, and answering
     * "nothing" because it is out of range would be a worse answer than showing
     * the shelf with the distance on it.
     */
    @Transactional(readOnly = true)
    public List<DiscoveryDtos.StorefrontSku> storeCatalog(Long storeId, Long outletId, String query) {
        String term = query == null ? "" : query.trim().toLowerCase();

        var stores = trading(directory.stores(List.of(storeId)).values());
        var sql = new StringBuilder();
        var args = new ArrayList<Object>();
        if (term.length() >= MIN_TERM) {
            sql.append("""
                       and (lower(k.name) like concat('%', ?, '%')
                            or lower(cp.name) like concat('%', ?, '%')
                            or lower(b.name) like concat('%', ?, '%'))
                    """);
            args.add(term);
            args.add(term);
            args.add(term);
        }

        var rows = fetchSkuRows(sql.toString(), args, stores, MAX_LIMIT);
        return decorate(rows, stores, outletId, storeId).skus();
    }

    /**
     * Suppliers, nearest first.
     *
     * <p>With a term this is a search; without one it is the directory of who can
     * deliver here, which is a question the app had no way to ask — the old
     * endpoint required two characters and returned nothing below that.
     *
     * <p><b>A term searches what they sell, not only what they are called.</b>
     * "pan" means paneer, and a restaurant typing it wants whoever stocks paneer —
     * matching supplier names alone answered a question nobody asked, because you
     * cannot search for a supplier by name unless you already know the name. So a
     * supplier qualifies by stocking something purchasable that matches, and
     * {@code matchingProductCount} says how much, so the row can explain itself.
     *
     * <p>Names still match, because the other half of the same screen is "find the
     * supplier I already deal with" — and the credit request screen asks for a
     * supplier by name and nothing else.
     *
     * @param radiusKm optional tighter cut. Suppliers beyond it are counted in
     *                 {@code beyondRadius} rather than silently dropped.
     */
    @Transactional(readOnly = true)
    public DiscoveryDtos.SupplierSearchPage searchSuppliers(String query, Long outletId,
                                                            BigDecimal radiusKm) {
        return searchSuppliers(query, outletId, radiusKm, null, 0, 50);
    }

    @Transactional(readOnly = true)
    public DiscoveryDtos.SupplierSearchPage searchSuppliers(String query, Long outletId,
                                                            BigDecimal radiusKm, String reach) {
        return searchSuppliers(query, outletId, radiusKm, reach, 0, 50);
    }

    @Transactional(readOnly = true)
    public DiscoveryDtos.SupplierSearchPage searchSuppliers(String query, Long outletId,
                                                            BigDecimal radiusKm, String reach,
                                                            Integer offset, Integer limit) {
        return searchSuppliers(query, outletId, radiusKm, reach, offset, limit, null, null, null);
    }

    @Transactional(readOnly = true)
    public DiscoveryDtos.SupplierSearchPage searchSuppliers(String query, Long outletId,
                                                            BigDecimal radiusKm, String reach,
                                                            Integer offset, Integer limit,
                                                            Boolean openNow, Integer minRating, String sort) {
        String effectiveSort = SupplierListFilters.requireSort(sort);
        SupplierListFilters.requireMinRating(minRating);

        String term = query == null ? "" : query.trim().toLowerCase();
        boolean filtered = term.length() >= MIN_TERM;
        boolean reachAll = "all".equalsIgnoreCase(reach);

        int effectiveOffset = Math.max(0, offset == null ? 0 : offset);
        int requestedLimit = limit == null ? 50 : limit;
        int effectiveLimit = Math.min(Math.max(1, requestedLimit), 100);

        var outlet = outletId == null ? null : directory.outlet(outletId).orElse(null);

        // D-208: the stores are common's, so who trades and what they are called
        // is decided here; only what they stock is asked of SQL.
        var tradingStores = trading(directory.allStores());
        if (tradingStores.isEmpty()) {
            return new DiscoveryDtos.SupplierSearchPage(List.of(), 0, 0, null);
        }
        var tradingIds = List.copyOf(tradingStores.keySet());
        Map<Long, Integer> productCounts = countPerStore("""
                select supplier_store_id, count(*)
                  from supplier_sku
                 where status = 'ACTIVE'
                   and supplier_store_id in (%s)
                 group by supplier_store_id""", List.of(), tradingIds);
        Map<Long, Integer> matchingCounts = filtered
                ? countPerStore(STOCKS_MATCHING, List.of(term, term, term), tradingIds)
                : Map.of();

        record Row(Long storeId, String supplierName, String storeName, String city,
                   BigDecimal latitude, BigDecimal longitude, int productCount,
                   int matchingCount) {
        }

        List<Row> rows = new ArrayList<>();
        for (var store : tradingStores.values()) {
            int matching = matchingCounts.getOrDefault(store.storeId(), 0);
            // A term matches the supplier's name, the store's name, or something
            // they stock that could be bought today.
            if (filtered && matching == 0
                    && !contains(store.supplierName(), term) && !contains(store.storeName(), term)) {
                continue;
            }
            rows.add(new Row(store.storeId(), store.supplierName(), store.storeName(),
                    store.city(), store.latitude(), store.longitude(),
                    productCounts.getOrDefault(store.storeId(), 0), matching));
        }

        if (rows.isEmpty()) {
            return new DiscoveryDtos.SupplierSearchPage(List.of(), 0, 0, null);
        }

        var storeInfo = tradingStores;
        var ratings = performance.forStores(rows.stream().map(Row::storeId).toList());

        record Sized(DiscoveryDtos.SupplierSearchResult result, Double distance) {
        }

        List<Sized> sized = new ArrayList<>();
        for (Row row : rows) {
            var store = storeInfo.get(row.storeId());
            Double distance = outlet == null ? null : Serviceability.distanceKm(
                    outlet.latitude(), outlet.longitude(), row.latitude(), row.longitude());

            boolean serves = reachAll || outlet == null || store == null
                    || serves(store, outlet.pincode(), distance);
            // A supplier who cannot deliver here is not a search result. The old
            // behaviour returned them with `serviceable: false`, which the app had
            // no way to render except as a row you cannot buy from.
            if (!serves) {
                continue;
            }

            var metrics = ratings.get(row.storeId());
            boolean isOpen = store == null || store.openNow();
            if (Boolean.TRUE.equals(openNow) && !isOpen) {
                continue;
            }

            BigDecimal avgRating = metrics == null ? null : metrics.averageRating().orElse(null);
            if (minRating != null) {
                if (avgRating == null || avgRating.compareTo(BigDecimal.valueOf(minRating)) < 0) {
                    continue;
                }
            }

            sized.add(new Sized(new DiscoveryDtos.SupplierSearchResult(
                    row.storeId(), row.supplierName(), row.storeName(), row.city(),
                    Serviceability.round(distance), true, row.productCount(),
                    row.matchingCount(),
                    avgRating,
                    metrics == null ? 0 : metrics.ratingCount(),
                    isOpen,
                    store == null ? null : store.opensAt()), distance));
        }

        // Sort: nearest (default) or rating (highest first, then nearest, then store id)
        if ("rating".equals(effectiveSort)) {
            sized.sort(Comparator.comparing(
                    (Sized s) -> s.result().averageRating() == null ? BigDecimal.valueOf(-1) : s.result().averageRating(),
                    Comparator.reverseOrder())
                    .thenComparing(s -> s.distance() == null ? Double.MAX_VALUE : s.distance())
                    .thenComparing(s -> s.result().supplierStoreId()));
        } else {
            // Nearest first, tie-breaker store ID, and a store with no coordinates last:
            // an unknown distance is not a short one.
            sized.sort(Comparator.comparing(
                    (Sized s) -> s.distance() == null ? Double.MAX_VALUE : s.distance())
                    .thenComparing(s -> s.result().supplierStoreId()));
        }

        int beyondRadius = 0;
        List<DiscoveryDtos.SupplierSearchResult> candidates;
        if (radiusKm != null) {
            var within = sized.stream()
                    .filter(s -> s.distance() == null
                            || BigDecimal.valueOf(s.distance()).compareTo(radiusKm) <= 0)
                    .toList();
            beyondRadius = sized.size() - within.size();
            candidates = within.stream().map(Sized::result).toList();
        } else {
            candidates = sized.stream().map(Sized::result).toList();
        }

        int total = candidates.size();
        List<DiscoveryDtos.SupplierSearchResult> pageItems;
        if (effectiveOffset >= total) {
            pageItems = List.of();
        } else {
            int toIndex = Math.min(effectiveOffset + effectiveLimit, total);
            pageItems = candidates.subList(effectiveOffset, toIndex);
        }

        Integer nextOffset = (effectiveOffset + effectiveLimit < total) ? effectiveOffset + effectiveLimit : null;
        return new DiscoveryDtos.SupplierSearchPage(pageItems, beyondRadius, total, nextOffset);
    }

    // ── internals ────────────────────────────────────────────────────────

    /** One purchasable offer, before distance and ratings are attached. */
    private record SkuRow(Long offerId, Long skuId, String skuName, String brandName,
                          String grade, BigDecimal packSize, String packUnit,
                          BigDecimal mrp, BigDecimal sellingPrice,
                          BigDecimal gstRate, String availability, BigDecimal availableQuantity,
                          String skuImageUrl, String canonicalImageUrl,
                          Long canonicalProductId, String canonicalProductName,
                          Long storeId, String supplierName, String storeName,
                          Long categoryId, String categoryName,
                          BigDecimal measureValue, String measureUnit) {
    }

    /**
     * Purchasable offers at these stores, cheapest first, at most {@code limit}.
     *
     * <p>D-208: the stores come from common already filtered to those taking
     * orders, and their names are attached here. Long store lists are asked in
     * chunks, each cheapest-first and capped, then merged and capped again —
     * the same rows one query over every store would have returned.
     */
    private List<SkuRow> fetchSkuRows(String extraWhere, List<Object> args,
                                      Map<Long, DiscoveryDirectory.StoreInfo> stores, Integer limit) {
        int capped = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (stores.isEmpty()) {
            return List.of();
        }

        var storeIds = List.copyOf(stores.keySet());
        List<SkuRow> rows = new ArrayList<>();
        for (int from = 0; from < storeIds.size(); from += CHUNK) {
            var chunk = storeIds.subList(from, Math.min(from + CHUNK, storeIds.size()));
            // The store ids come first: their "in (...)" precedes the caller's filter in the SQL.
            var chunkArgs = new ArrayList<Object>(chunk);
            chunkArgs.addAll(args);
            fetchSkuChunk(extraWhere, chunkArgs, chunk.size(), capped, stores, rows);
        }
        if (storeIds.size() > CHUNK) {
            rows.sort(Comparator.comparing(SkuRow::sellingPrice,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
            if (rows.size() > capped) {
                rows = new ArrayList<>(rows.subList(0, capped));
            }
        }
        return rows;
    }

    private void fetchSkuChunk(String extraWhere, List<Object> args, int storeCount, int capped,
                               Map<Long, DiscoveryDirectory.StoreInfo> stores, List<SkuRow> rows) {
        String placeholders = String.join(",", java.util.Collections.nCopies(storeCount, "?"));
        jdbc.query("""
                select f.id, k.id, k.name, b.name,
                       k.pack_size, k.pack_unit, f.selling_price, f.gst_rate,
                       f.availability, f.available_quantity,
                       k.image_url, cp.image_url,
                       cp.id, cp.name,
                       f.supplier_store_id,
                       cp.category_id, pc.name,
                       k.measure_value, k.measure_unit,
                       k.grade, coalesce(f.mrp, k.mrp) as mrp
                  from supplier_offer f
                  join supplier_sku k on k.id = f.supplier_sku_id
                  join canonical_product cp on cp.id = k.canonical_product_id
                  left join brand b on b.id = k.brand_id
                  left join product_category pc on pc.id = cp.category_id
                 where f.status = 'ACTIVE'
                   and k.status = 'ACTIVE'
                   and (f.effective_to is null or f.effective_to > now())
                   and f.supplier_store_id in (%s)
                %s
                 order by f.selling_price
                 limit %d
                """.formatted(placeholders, extraWhere, capped),
                rs -> {
                    var store = stores.get(rs.getLong(15));
                    rows.add(new SkuRow(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(20), rs.getBigDecimal(5), rs.getString(6),
                            rs.getBigDecimal(21), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getString(9), rs.getBigDecimal(10),
                            rs.getString(11), rs.getString(12),
                            rs.getLong(13), rs.getString(14),
                            rs.getLong(15), store.supplierName(), store.storeName(),
                            (Long) rs.getObject(16), rs.getString(17),
                            rs.getBigDecimal(18), rs.getString(19)));
                },
                args.toArray());
    }

    private record Decorated(List<DiscoveryDtos.StorefrontSku> skus) {
    }

    /**
     * Attaches distance, opening state and rating, and drops what this outlet
     * cannot be served by.
     *
     * @param onlyStore when set, serviceability is not applied — the caller asked
     *                  for this store specifically.
     */
    private Decorated decorate(List<SkuRow> rows, Map<Long, DiscoveryDirectory.StoreInfo> storeInfo,
                               Long outletId, Long onlyStore) {
        if (rows.isEmpty()) {
            return new Decorated(List.of());
        }

        var storeIds = rows.stream().map(SkuRow::storeId).distinct().toList();
        var ratings = performance.forStores(storeIds);
        var outlet = outletId == null ? null : directory.outlet(outletId).orElse(null);

        Map<String, List<DiscoveryDtos.BrandOption>> brandOptionsByStoreProduct = new HashMap<>();
        for (SkuRow row : rows) {
            String key = row.storeId() + ":" + row.canonicalProductId();
            BigDecimal incl = row.sellingPrice() != null
                    ? packInclusiveOfGst(row.sellingPrice(), row.gstRate()) : null;
            BigDecimal discountAmount = Pricing.discountAmount(row.mrp(), row.sellingPrice());
            Integer discountPercent = Pricing.discountPercent(row.mrp(), row.sellingPrice());
            brandOptionsByStoreProduct.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new DiscoveryDtos.BrandOption(
                            row.skuId(),
                            row.offerId(),
                            row.skuName(),
                            row.brandName(),
                            row.grade(),
                            row.packSize(),
                            row.packUnit(),
                            row.mrp(),
                            row.sellingPrice(),
                            discountAmount,
                            discountPercent,
                            row.gstRate(),
                            incl,
                            blankToNull(row.skuImageUrl()) != null
                                    ? row.skuImageUrl() : blankToNull(row.canonicalImageUrl()),
                            row.availability(),
                            row.availableQuantity(),
                            row.measureValue(),
                            row.measureUnit()
                    ));
        }

        // Lowest priced brand option first
        for (List<DiscoveryDtos.BrandOption> options : brandOptionsByStoreProduct.values()) {
            options.sort(Comparator.comparing(DiscoveryDtos.BrandOption::sellingPrice,
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }

        record Sized(DiscoveryDtos.StorefrontSku sku, Double distance) {
        }

        List<Sized> out = new ArrayList<>();
        for (SkuRow row : rows) {
            var store = storeInfo.get(row.storeId());
            Double distance = outlet == null || store == null ? null : Serviceability.distanceKm(
                    outlet.latitude(), outlet.longitude(), store.latitude(), store.longitude());

            if (onlyStore == null && outlet != null && store != null
                    && !serves(store, outlet.pincode(), distance)) {
                continue;
            }

            var metrics = ratings.get(row.storeId());
            String key = row.storeId() + ":" + row.canonicalProductId();
            List<DiscoveryDtos.BrandOption> brandOptions = brandOptionsByStoreProduct.getOrDefault(key, List.of());
            BigDecimal rowDiscountAmount = Pricing.discountAmount(row.mrp(), row.sellingPrice());
            Integer rowDiscountPercent = Pricing.discountPercent(row.mrp(), row.sellingPrice());

            out.add(new Sized(new DiscoveryDtos.StorefrontSku(
                    row.offerId(), row.skuId(), row.skuName(), row.brandName(),
                    row.grade(),
                    row.packSize(), row.packUnit(),
                    row.mrp(),
                    row.sellingPrice(),
                    rowDiscountAmount,
                    rowDiscountPercent,
                    row.gstRate(),
                    row.availability(), row.availableQuantity(),
                    // The SKU's own picture when the supplier uploaded one, the
                    // canonical product's otherwise. Blank is not a URL — an empty
                    // string here renders as a broken image rather than a fallback.
                    blankToNull(row.skuImageUrl()) != null
                            ? row.skuImageUrl() : blankToNull(row.canonicalImageUrl()),
                    row.canonicalProductId(), row.canonicalProductName(),
                    row.storeId(), row.supplierName(), row.storeName(),
                    Serviceability.round(distance),
                    store == null || store.openNow(),
                    store == null ? null : store.opensAt(),
                    store == null ? null : store.preparationMinutes(),
                    metrics == null ? null : metrics.averageRating().orElse(null),
                    metrics == null ? 0 : metrics.ratingCount(),
                    row.categoryId(), row.categoryName(),
                    row.measureValue(), row.measureUnit(),
                    brandOptions), distance));
        }

        // Cheapest first is the SQL order and the useful one for a buyer. Distance
        // is shown but does not reorder: a supplier 200m away charging twice as
        // much is not the better answer to "who sells curd".
        return new Decorated(out.stream().map(Sized::sku).toList());
    }

    /** The stores that take orders, by id. D-208: was {@code s.status} and {@code o.lifecycle_status} in SQL. */
    private static Map<Long, DiscoveryDirectory.StoreInfo> trading(
            java.util.Collection<DiscoveryDirectory.StoreInfo> stores) {
        Map<Long, DiscoveryDirectory.StoreInfo> out = new java.util.LinkedHashMap<>();
        stores.stream().filter(DiscoveryDirectory.StoreInfo::tradeable)
                .forEach(store -> out.put(store.storeId(), store));
        return out;
    }

    /** A {@code (store id, count)} query over chunks of store ids; {@code sql} has one {@code (%s)} for them. */
    private Map<Long, Integer> countPerStore(String sql, List<Object> leadingArgs, List<Long> storeIds) {
        Map<Long, Integer> counts = new HashMap<>();
        for (int from = 0; from < storeIds.size(); from += CHUNK) {
            var chunk = storeIds.subList(from, Math.min(from + CHUNK, storeIds.size()));
            var args = new ArrayList<Object>(leadingArgs);
            args.addAll(chunk);
            // replace, not formatted(): the term match carries literal '%' signs.
            jdbc.query(sql.replace("(%s)",
                            "(" + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")"),
                    rs -> {
                        counts.put(rs.getLong(1), rs.getInt(2));
                    },
                    args.toArray());
        }
        return counts;
    }

    /** SQL's {@code lower(name) like '%term%'}: a missing name matches nothing. */
    private static boolean contains(String name, String term) {
        return name != null && name.toLowerCase().contains(term);
    }

    /** Doc 07 §13: a declared pincode list wins, then the store's own radius. D-138. */
    private boolean serves(DiscoveryDirectory.StoreInfo store, String outletPincode, Double distanceKm) {
        return serviceabilityPolicy.serves(store, outletPincode, distanceKm);
    }

    private static BigDecimal packInclusiveOfGst(BigDecimal sellingPrice, BigDecimal gstRate) {
        if (sellingPrice == null) {
            return null;
        }
        BigDecimal rate = gstRate == null ? BigDecimal.ZERO : gstRate;
        BigDecimal value = Pricing.lineItemValue(sellingPrice, BigDecimal.ONE);
        return Pricing.lineTotal(value, Pricing.lineGst(value, rate));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
