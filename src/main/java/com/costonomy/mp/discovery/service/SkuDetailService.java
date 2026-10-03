package com.costonomy.mp.discovery.service;

import com.costonomy.mp.catalog.domain.SkuReview;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.domain.SupplierSku;
import com.costonomy.mp.catalog.domain.SupplierSkuImage;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.catalog.repository.SkuReviewRepository;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuImageRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.catalog.service.CatalogDirectory;
import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import com.costonomy.mp.procurement.domain.Pricing;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * One pack, in full — the page a kitchen decides on. D-096.
 *
 * <p>The shelf row and the comparison card answer "which of these is cheapest".
 * This answers the question underneath it: what <em>is</em> this, is it the
 * right size, does it fit, and did it work for anybody else.
 *
 * <p><b>The commercial half comes from the same place the shelf reads.</b> A
 * detail page that priced a SKU differently from the row that led to it would
 * be the worst possible place in the app to disagree, so price, GST and
 * availability are the live offer and nothing here recomputes them.
 */
@Service
@RequiredArgsConstructor
public class SkuDetailService {

    /** How many reviews the page carries. A full history belongs behind paging. */
    private static final int REVIEWS = 20;

    private final SupplierSkuRepository skus;
    private final SupplierOfferRepository offers;
    private final SupplierSkuImageRepository images;
    private final SkuReviewRepository reviews;
    private final CanonicalProductRepository products;
    private final DiscoveryDirectory directory;
    private final CatalogDirectory brands;
    private final SupplierPerformanceProvider performance;
    private final AppConfigService config;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public DiscoveryDtos.SkuDetail detail(Long skuId, Long outletId) {
        var sku = skus.findById(skuId)
                .orElseThrow(() -> new NotFoundException("SupplierSku", skuId));

        var offer = offers.findBySupplierSkuIdAndStatus(skuId, "ACTIVE").orElse(null);
        var product = products.findById(sku.getCanonicalProductId()).orElse(null);
        var store = directory.stores(List.of(sku.getSupplierStoreId()))
                .get(sku.getSupplierStoreId());
        var outlet = outletId == null ? null : directory.outlet(outletId).orElse(null);

        Double distance = outlet == null || store == null ? null : Serviceability.distanceKm(
                outlet.latitude(), outlet.longitude(), store.latitude(), store.longitude());

        Integer eta = store == null ? null : Serviceability.estimateMinutes(
                store.preparationMinutes(), distance,
                config.getDecimal("eta.averageSpeedKmph", new BigDecimal("20")),
                config.getInt("eta.dispatchOverheadMinutes", 15));

        var published = reviews.findBySupplierSkuIdAndModerationStatusOrderByCreatedAtDesc(
                skuId, "PUBLISHED");
        var metrics = performance.forStores(List.of(sku.getSupplierStoreId()))
                .get(sku.getSupplierStoreId());

        String brandName = sku.getBrandId() == null ? null
                : brands.brandNames(List.of(sku.getBrandId())).get(sku.getBrandId());

        return new DiscoveryDtos.SkuDetail(
                sku.getId(),
                offer == null ? null : offer.getId(),
                sku.getName(), brandName,
                sku.getPackSize(), sku.getPackUnit(),
                sku.getMeasureValue(), sku.getMeasureUnit(),
                offer == null ? null : offer.getSellingPrice(),
                offer == null ? null : offer.getGstRate(),
                // The one figure computed here, and computed the way the order
                // will be: price plus GST on it, rounded once. Guardrail 3 puts
                // the sum on the server, not in the app.
                offer == null ? null : inclusive(offer),
                offer == null ? null : offer.getAvailability(),
                offer == null ? null : offer.getAvailableQuantity(),
                // The SKU's own picture, else the product's — a listing has a
                // face even when the supplier never photographed their pack.
                sku.getImageUrl() != null ? sku.getImageUrl()
                        : product == null ? null : product.getImageUrl(),
                images.findBySupplierSkuIdOrderByPositionAscIdAsc(skuId).stream()
                        .map(SupplierSkuImage::getUrl).toList(),
                sku.getYoutubeUrl(), sku.getDescription(),
                sku.getLengthCm(), sku.getWidthCm(), sku.getHeightCm(), sku.getWeightGrams(),
                sku.getCanonicalProductId(),
                product == null ? null : product.getName(),
                product == null ? null : product.getCategoryId(),
                categoryName(product == null ? null : product.getCategoryId()),
                sku.getSupplierStoreId(),
                store == null ? null : store.storeName(),
                store == null ? null : store.supplierName(),
                Serviceability.round(distance),
                store != null && store.openNow(),
                store == null ? null : store.opensAt(),
                eta,
                metrics == null ? null : metrics.averageRating().orElse(null),
                metrics == null ? 0 : metrics.ratingCount(),
                average(published), published.size(),
                published.stream().limit(REVIEWS).map(this::toResponse).toList(),
                siblings(sku),
                brandOptions(sku, product == null ? null : product.getImageUrl()));
    }

    /**
     * Other packs of the same product from the same store.
     *
     * <p>The comparison shows one card per supplier — their best pack — so this
     * is the only place the rest of their range is reachable from. Without it a
     * supplier listing a 200g tub beside a 5kg block has effectively hidden one
     * of them.
     */
    private List<DiscoveryDtos.SkuSibling> siblings(SupplierSku sku) {
        return jdbc.query("""
                select k.id, k.name, k.pack_size, k.pack_unit,
                       f.selling_price, k.image_url, f.availability,
                       b.name as brand_name, f.gst_rate, f.id as offer_id
                  from supplier_sku k
                  left join supplier_offer f
                         on f.supplier_sku_id = k.id and f.status = 'ACTIVE'
                  left join brand b
                         on b.id = k.brand_id
                 where k.supplier_store_id = ?
                   and k.canonical_product_id = ?
                   and k.id <> ?
                   and k.status = 'ACTIVE'
                 order by f.selling_price asc
                """,
                (rs, i) -> {
                    BigDecimal price = rs.getBigDecimal(5);
                    BigDecimal gst = rs.getBigDecimal(9);
                    BigDecimal incl = price != null ? inclusivePrice(price, gst) : null;
                    return new DiscoveryDtos.SkuSibling(
                            rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                            price, rs.getString(6), rs.getString(7),
                            rs.getString(8), gst, incl, (Long) rs.getObject(10));
                },
                sku.getSupplierStoreId(), sku.getCanonicalProductId(), sku.getId());
    }

    /**
     * All brand options from this supplier for this item, sorted with lowest priced first.
     */
    private List<DiscoveryDtos.BrandOption> brandOptions(SupplierSku sku, String canonicalImageUrl) {
        return jdbc.query("""
                select k.id, f.id, k.name, b.name,
                       k.pack_size, k.pack_unit, f.selling_price, f.gst_rate,
                       k.image_url, f.availability, f.available_quantity,
                       k.measure_value, k.measure_unit
                  from supplier_sku k
                  left join supplier_offer f
                         on f.supplier_sku_id = k.id and f.status = 'ACTIVE'
                  left join brand b
                         on b.id = k.brand_id
                 where k.supplier_store_id = ?
                   and k.canonical_product_id = ?
                   and k.status = 'ACTIVE'
                 order by f.selling_price asc
                """,
                (rs, i) -> {
                    BigDecimal price = rs.getBigDecimal(7);
                    BigDecimal gst = rs.getBigDecimal(8);
                    BigDecimal incl = price != null ? inclusivePrice(price, gst) : null;
                    String skuImg = rs.getString(9);
                    String img = (skuImg != null && !skuImg.isBlank()) ? skuImg : canonicalImageUrl;
                    return new DiscoveryDtos.BrandOption(
                            rs.getLong(1),
                            (Long) rs.getObject(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getBigDecimal(5),
                            rs.getString(6),
                            price,
                            gst,
                            incl,
                            img,
                            rs.getString(10),
                            rs.getBigDecimal(11),
                            rs.getBigDecimal(12),
                            rs.getString(13)
                    );
                },
                sku.getSupplierStoreId(), sku.getCanonicalProductId());
    }

    private static BigDecimal inclusivePrice(BigDecimal sellingPrice, BigDecimal gstRate) {
        BigDecimal rate = gstRate == null ? BigDecimal.ZERO : gstRate;
        BigDecimal value = Pricing.lineItemValue(sellingPrice, BigDecimal.ONE);
        return Pricing.lineTotal(value, Pricing.lineGst(value, rate));
    }

    private String categoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        var rows = jdbc.queryForList(
                "select name from product_category where id = ?", String.class, categoryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BigDecimal inclusive(SupplierOffer offer) {
        var value = Pricing.lineItemValue(offer.getSellingPrice(), BigDecimal.ONE);
        return Pricing.lineTotal(value, Pricing.lineGst(value, offer.getGstRate()));
    }

    /**
     * The mean of what is published.
     *
     * <p>Null when nobody has reviewed it. Doc 07 §4: a pack with no reviews has
     * no score, and averaging nothing into a number is how something new ends up
     * looking excellent or terrible for no reason.
     */
    private static BigDecimal average(List<SkuReview> published) {
        if (published.isEmpty()) {
            return null;
        }
        var total = published.stream()
                .map(review -> BigDecimal.valueOf(review.getRating()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(published.size()), 1, RoundingMode.HALF_UP);
    }

    private DiscoveryDtos.SkuReviewResponse toResponse(SkuReview review) {
        var names = jdbc.queryForList(
                "select name from outlet where id = ?", String.class, review.getOutletId());
        return new DiscoveryDtos.SkuReviewResponse(
                review.getId(), review.getRating(), review.getComment(),
                names.isEmpty() ? null : names.get(0),
                review.getCreatedAt());
    }

}
