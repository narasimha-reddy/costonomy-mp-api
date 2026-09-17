package com.costonomy.mp.catalog.service;

import com.costonomy.mp.catalog.domain.*;
import com.costonomy.mp.catalog.repository.*;
import com.costonomy.mp.catalog.web.dto.CatalogDtos;
import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The canonical catalog, as a restaurant sees it.
 *
 * <p>Reads are not scoped to a tenant: the catalog is the marketplace, and any
 * authenticated restaurant user may browse it. Scope matters on the
 * <em>supplier</em> side, where a store's own SKUs are private — that is
 * {@link SupplierCatalogService}.
 */
@Service
@RequiredArgsConstructor
public class CatalogQueryService {

    /** Assumed reach for a store that never declared one. Matches the feed's. */
    private static final BigDecimal DEFAULT_RADIUS_KM = BigDecimal.valueOf(25);

    private static final int MAX_SEARCH_RESULTS = 50;

    private final ProductCategoryRepository categories;
    private final BrandRepository brands;
    private final CanonicalProductRepository products;
    private final CanonicalProductAliasRepository aliases;
    private final SupplierOfferRepository offers;
    private final SupplierSkuRepository skus;
    private final CatalogDirectory directory;

    @Transactional(readOnly = true)
    public List<CatalogDtos.CategoryResponse> listCategories() {
        return categories.findByStatusOrderByDisplayOrderAscNameAsc("ACTIVE").stream()
                .map(c -> new CatalogDtos.CategoryResponse(
                        c.getId(), c.getParentId(), c.getName(), c.getSlug(),
                        c.getImageUrl(), c.getDisplayOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.BrandResponse> listBrands() {
        return brands.findByStatusOrderByNameAsc("ACTIVE").stream()
                .map(b -> new CatalogDtos.BrandResponse(b.getId(), b.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.ProductResponse> listProducts(Long categoryId, int page, int size,
                                                          Long outletId) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        var found = categoryId == null
                ? products.findByStatus("ACTIVE", pageable)
                : products.findByStatusAndCategoryId("ACTIVE", categoryId, pageable);

        return describe(found.getContent(), outletId);
    }

    /**
     * Search canonical products by name or alias.
     *
     * <p>Product-first, per doc 01 §25 and §23A.11: the restaurant searches for
     * paneer, then compares who sells it — not the other way round.
     */
    @Transactional(readOnly = true)
    public List<CatalogDtos.ProductResponse> searchProducts(String term, Long outletId) {
        String normalized = Normalization.normalize(term);
        if (normalized.isBlank()) {
            return List.of();
        }
        return describe(
                products.searchByPrefix(normalized, PageRequest.of(0, MAX_SEARCH_RESULTS)),
                outletId);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.ProductResponse getProduct(Long productId, Long outletId) {
        var product = products.findById(productId)
                .orElseThrow(() -> new NotFoundException("CanonicalProduct", productId));
        return describe(List.of(product), outletId).get(0);
    }

    /**
     * Every purchasable offer for a product — the supplier comparison (§23A.13).
     *
     * <p>Ordered cheapest-first as a deterministic baseline only. Best Value
     * ranking (doc 07 §4) arrives in Phase 6 and weighs availability, ETA, fill
     * rate, on-time performance and rating alongside price. Commission is never an
     * input to either ordering (guardrail 9).
     */
    @Transactional(readOnly = true)
    public List<CatalogDtos.OfferResponse> offersForProduct(Long productId) {
        if (!products.existsById(productId)) {
            throw new NotFoundException("CanonicalProduct", productId);
        }

        var purchasable = offers.findPurchasableForProduct(productId);
        if (purchasable.isEmpty()) {
            return List.of();
        }

        Map<Long, SupplierSku> skuById = new HashMap<>();
        skus.findAllById(purchasable.stream().map(SupplierOffer::getSupplierSkuId).toList())
                .forEach(sku -> skuById.put(sku.getId(), sku));

        var brandNames = directory.brandNames(skuById.values().stream()
                .map(SupplierSku::getBrandId)
                .filter(java.util.Objects::nonNull)
                .toList());
        var storeInfo = directory.storeInfo(
                purchasable.stream().map(SupplierOffer::getSupplierStoreId).distinct().toList());

        return purchasable.stream()
                .filter(offer -> {
                    var sku = skuById.get(offer.getSupplierSkuId());
                    // An offer whose SKU was deactivated is not purchasable, even
                    // though the offer row is still ACTIVE. Filtering here rather
                    // than in SQL keeps the rule visible.
                    return sku != null && "ACTIVE".equals(sku.getStatus());
                })
                .filter(offer -> {
                    var store = storeInfo.get(offer.getSupplierStoreId());
                    // Doc 03 §15: a supplier order can only be created against an
                    // ACTIVE store, so an offline store's offers must not be shown
                    // as purchasable in the first place.
                    return store != null && store.active();
                })
                .map(offer -> {
                    var sku = skuById.get(offer.getSupplierSkuId());
                    var store = storeInfo.get(offer.getSupplierStoreId());
                    return new CatalogDtos.OfferResponse(
                            offer.getId(), sku.getId(), offer.getSupplierStoreId(),
                            store.storeName(), store.supplierName(),
                            sku.getName(),
                            sku.getBrandId() == null ? null : brandNames.get(sku.getBrandId()),
                            sku.getPackSize(), sku.getPackUnit(),
                            offer.getSellingPrice(), offer.getGstRate(),
                            offer.getAvailability(), offer.getAvailableQuantity(),
                            store.responseSlaSeconds(), store.preparationMinutes());
                })
                .toList();
    }

    /**
     * How many suppliers a restaurant could actually buy each product from, and
     * for how little.
     *
     * <p><b>Counted the way the comparison screen counts.</b> These two figures
     * are on every card, and until now they came from the offer rows alone: an
     * offer under a store that was suspended, or under an organisation still
     * awaiting verification, was counted as a supplier you could order from. The
     * card said four and the product screen then showed three.
     *
     * <p>With an {@code outletId} it also drops suppliers who do not deliver
     * there, which is what "3 suppliers" was always taken to mean. Without one —
     * an unauthenticated browse, or a caller that has no outlet in hand — the
     * figure is the platform-wide one, and says so by omission rather than by
     * pretending to be local.
     *
     * <p>Batched: a page of thirty products is one offer query and one store
     * query, not sixty.
     */
    private List<CatalogDtos.ProductResponse> describe(List<CanonicalProduct> found, Long outletId) {
        if (found.isEmpty()) {
            return List.of();
        }

        var productIds = found.stream().map(CanonicalProduct::getId).toList();
        var offersByProduct = offers.findPurchasableForProducts(productIds).stream()
                .collect(Collectors.groupingBy(SupplierOffer::getCanonicalProductId));

        var storeIds = offersByProduct.values().stream()
                .flatMap(List::stream)
                .map(SupplierOffer::getSupplierStoreId)
                .distinct()
                .toList();
        var stores = directory.storeInfo(storeIds);
        var outlet = directory.outlet(outletId).orElse(null);

        return found.stream()
                .map(product -> {
                    var buyable = offersByProduct.getOrDefault(product.getId(), List.of()).stream()
                            .filter(offer -> {
                                var store = stores.get(offer.getSupplierStoreId());
                                return store != null && store.active()
                                        && (outlet == null || serves(store, outlet));
                            })
                            .toList();

                    BigDecimal lowest = buyable.stream()
                            .map(SupplierOffer::getSellingPrice)
                            .min(Comparator.naturalOrder())
                            .orElse(null);

                    return toProductResponse(product, buyable.size(), lowest);
                })
                .toList();
    }

    /**
     * Whether a store delivers to an outlet. Doc 07 §13.
     *
     * <p>Deliberately the same rule as the recommendation feed: a declared pincode
     * list overrides geography entirely, then the store's own radius, and a store
     * whose address has not been geocoded is included rather than made invisible
     * by a gap in our data.
     */
    private boolean serves(CatalogDirectory.StoreInfo store, CatalogDirectory.OutletInfo outlet) {
        if (!store.serviceablePincodes().isEmpty()) {
            return outlet.pincode() != null && store.serviceablePincodes().contains(outlet.pincode());
        }
        Double distance = Serviceability.distanceKm(
                outlet.latitude(), outlet.longitude(), store.latitude(), store.longitude());
        if (distance == null) {
            return true;
        }
        BigDecimal radius = store.maxDeliveryRadiusKm() == null
                ? DEFAULT_RADIUS_KM : store.maxDeliveryRadiusKm();
        return BigDecimal.valueOf(distance).compareTo(radius) <= 0;
    }

    private CatalogDtos.ProductResponse toProductResponse(
            CanonicalProduct product, int offerCount, BigDecimal lowestPrice) {
        var productAliases = aliases.findByCanonicalProductId(product.getId()).stream()
                .map(CanonicalProductAlias::getAlias)
                .toList();

        String categoryName = product.getCategoryId() == null ? null
                : categories.findById(product.getCategoryId())
                        .map(ProductCategory::getName).orElse(null);

        return new CatalogDtos.ProductResponse(
                product.getId(), product.getName(), product.getCategoryId(), categoryName,
                product.getDescription(), product.getBaseUnit(), product.getBasePackSize(),
                product.getImageUrl(), productAliases, offerCount, lowestPrice);
    }

    /** Resolve or create a brand by name. Shared with import. */
    @Transactional
    public Long resolveBrandId(String brandName) {
        if (brandName == null || brandName.isBlank()) {
            return null;
        }
        String normalized = Normalization.normalize(brandName);
        return brands.findByNormalizedName(normalized)
                .map(Brand::getId)
                .orElseGet(() -> {
                    // Brands are created on demand rather than curated up front:
                    // a supplier uploading a catalog should not be blocked because
                    // their regional brand is not on a list. Canonical *products*
                    // are the opposite — those stay platform-owned (doc 01 §7),
                    // because they are the axis of comparison.
                    var brand = new Brand();
                    brand.setName(brandName.trim());
                    brand.setNormalizedName(normalized);
                    return brands.save(brand).getId();
                });
    }

    Map<Long, String> productNames(List<Long> productIds) {
        Map<Long, String> names = new HashMap<>();
        products.findAllById(productIds).forEach(p -> names.put(p.getId(), p.getName()));
        return names;
    }
}
