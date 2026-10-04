package com.costonomy.mp.discovery.service;

import com.costonomy.mp.common.domain.Serviceability;
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
     * Whether a store stocks something matching the term that you could buy today.
     *
     * <p>Takes three bound parameters, all the same term.
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
            from supplier_sku k
              join canonical_product cp on cp.id = k.canonical_product_id
              join supplier_offer f on f.supplier_sku_id = k.id
                   and f.status = 'ACTIVE'
                   and f.availability = 'AVAILABLE'
                   and (f.effective_to is null or f.effective_to > now())
              left join brand b on b.id = k.brand_id
             where k.supplier_store_id = s.id
               and k.status = 'ACTIVE'
               and (lower(k.name) like concat('%', ?, '%')
                    or lower(cp.name) like concat('%', ?, '%')
                    or lower(b.name) like concat('%', ?, '%'))""";

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

        var rows = fetchSkuRows("""
                   and (lower(k.name) like concat('%', ?, '%')
                        or lower(cp.name) like concat('%', ?, '%')
                        or lower(b.name) like concat('%', ?, '%'))
                """, List.of(term, term, term), limit);

        return decorate(rows, outletId, null).skus();
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

        var sql = new StringBuilder("   and s.id = ?\n");
        var args = new ArrayList<Object>();
        args.add(storeId);
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

        var rows = fetchSkuRows(sql.toString(), args, MAX_LIMIT);
        return decorate(rows, outletId, storeId).skus();
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
        String term = query == null ? "" : query.trim().toLowerCase();
        boolean filtered = term.length() >= MIN_TERM;
        boolean reachAll = "all".equalsIgnoreCase(reach);

        int effectiveOffset = Math.max(0, offset == null ? 0 : offset);
        int requestedLimit = limit == null ? 50 : limit;
        int effectiveLimit = Math.min(Math.max(1, requestedLimit), 100);

        var outlet = outletId == null ? null : directory.outlet(outletId).orElse(null);

        var args = new ArrayList<Object>();

        var sql = new StringBuilder("""
                select s.id, o.display_name, s.name, s.city, s.latitude, s.longitude,
                       (select count(*) from supplier_sku k
                         where k.supplier_store_id = s.id and k.status = 'ACTIVE') as product_count,
                """);

        // Counted in the select and tested again in the where, rather than counted
        // once and filtered on the alias: MySQL cannot see a select alias in a
        // where clause, and HAVING without a GROUP BY is a subtler thing to read
        // than the same predicate written twice.
        if (filtered) {
            sql.append("       (select count(*) ").append(STOCKS_MATCHING).append(") as matching_count\n");
            args.add(term);
            args.add(term);
            args.add(term);
        } else {
            sql.append("       0 as matching_count\n");
        }

        sql.append("""
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                 where s.status = 'ACTIVE'
                   and o.lifecycle_status = 'ACTIVE'
                """);

        if (filtered) {
            sql.append("   and (lower(o.display_name) like concat('%', ?, '%')\n")
               .append("        or lower(s.name) like concat('%', ?, '%')\n")
               .append("        or exists (select 1 ").append(STOCKS_MATCHING).append("))\n");
            args.add(term);
            args.add(term);
            args.add(term);
            args.add(term);
            args.add(term);
        }

        record Row(Long storeId, String supplierName, String storeName, String city,
                   BigDecimal latitude, BigDecimal longitude, int productCount,
                   int matchingCount) {
        }

        List<Row> rows = new ArrayList<>();
        jdbc.query(sql.toString(),
                rs -> {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                            rs.getInt(7), rs.getInt(8)));
                },
                args.toArray());

        if (rows.isEmpty()) {
            return new DiscoveryDtos.SupplierSearchPage(List.of(), 0, 0, null);
        }

        var storeInfo = directory.stores(rows.stream().map(Row::storeId).toList());
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
            sized.add(new Sized(new DiscoveryDtos.SupplierSearchResult(
                    row.storeId(), row.supplierName(), row.storeName(), row.city(),
                    Serviceability.round(distance), true, row.productCount(),
                    row.matchingCount(),
                    metrics == null ? null : metrics.averageRating().orElse(null),
                    metrics == null ? 0 : metrics.ratingCount(),
                    store == null || store.openNow(),
                    store == null ? null : store.opensAt()), distance));
        }

        // Nearest first, tie-breaker store ID, and a store with no coordinates last:
        // an unknown distance is not a short one.
        sized.sort(Comparator.comparing(
                (Sized s) -> s.distance() == null ? Double.MAX_VALUE : s.distance())
                .thenComparing(s -> s.result().supplierStoreId()));

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

    private List<SkuRow> fetchSkuRows(String extraWhere, List<Object> args, Integer limit) {
        int capped = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<SkuRow> rows = new ArrayList<>();
        jdbc.query("""
                select f.id, k.id, k.name, b.name,
                       k.pack_size, k.pack_unit, f.selling_price, f.gst_rate,
                       f.availability, f.available_quantity,
                       k.image_url, cp.image_url,
                       cp.id, cp.name,
                       s.id, o.display_name, s.name,
                       cp.category_id, pc.name,
                       k.measure_value, k.measure_unit,
                       k.grade, coalesce(f.mrp, k.mrp) as mrp
                  from supplier_offer f
                  join supplier_sku k on k.id = f.supplier_sku_id
                  join canonical_product cp on cp.id = k.canonical_product_id
                  join supplier_store s on s.id = f.supplier_store_id
                  join supplier_organization o on o.id = s.supplier_organization_id
                  left join brand b on b.id = k.brand_id
                  left join product_category pc on pc.id = cp.category_id
                 where f.status = 'ACTIVE'
                   and k.status = 'ACTIVE'
                   and s.status = 'ACTIVE'
                   and o.lifecycle_status = 'ACTIVE'
                   and (f.effective_to is null or f.effective_to > now())
                %s
                 order by f.selling_price
                 limit %d
                """.formatted(extraWhere, capped),
                rs -> {
                    rows.add(new SkuRow(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(22), rs.getBigDecimal(5), rs.getString(6),
                            rs.getBigDecimal(23), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getString(9), rs.getBigDecimal(10),
                            rs.getString(11), rs.getString(12),
                            rs.getLong(13), rs.getString(14),
                            rs.getLong(15), rs.getString(16), rs.getString(17),
                            (Long) rs.getObject(18), rs.getString(19),
                            rs.getBigDecimal(20), rs.getString(21)));
                },
                args.toArray());
        return rows;
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
    private Decorated decorate(List<SkuRow> rows, Long outletId, Long onlyStore) {
        if (rows.isEmpty()) {
            return new Decorated(List.of());
        }

        var storeIds = rows.stream().map(SkuRow::storeId).distinct().toList();
        var storeInfo = directory.stores(storeIds);
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
