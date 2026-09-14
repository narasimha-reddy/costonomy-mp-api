package com.costonomy.mp.catalog.service;

import com.costonomy.mp.catalog.domain.*;
import com.costonomy.mp.catalog.repository.*;
import com.costonomy.mp.catalog.web.dto.CatalogDtos;
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
    public List<CatalogDtos.ProductResponse> listProducts(Long categoryId, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        var found = categoryId == null
                ? products.findByStatus("ACTIVE", pageable)
                : products.findByStatusAndCategoryId("ACTIVE", categoryId, pageable);

        return found.getContent().stream().map(this::toProductResponse).toList();
    }

    /**
     * Search canonical products by name or alias.
     *
     * <p>Product-first, per doc 01 §25 and §23A.11: the restaurant searches for
     * paneer, then compares who sells it — not the other way round.
     */
    @Transactional(readOnly = true)
    public List<CatalogDtos.ProductResponse> searchProducts(String term) {
        String normalized = Normalization.normalize(term);
        if (normalized.isBlank()) {
            return List.of();
        }
        return products.searchByPrefix(normalized, PageRequest.of(0, MAX_SEARCH_RESULTS)).stream()
                .map(this::toProductResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CatalogDtos.ProductResponse getProduct(Long productId) {
        var product = products.findById(productId)
                .orElseThrow(() -> new NotFoundException("CanonicalProduct", productId));
        return toProductResponse(product);
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

    private CatalogDtos.ProductResponse toProductResponse(CanonicalProduct product) {
        var productAliases = aliases.findByCanonicalProductId(product.getId()).stream()
                .map(CanonicalProductAlias::getAlias)
                .toList();

        var purchasable = offers.findPurchasableForProduct(product.getId());
        BigDecimal lowest = purchasable.stream()
                .map(SupplierOffer::getSellingPrice)
                .min(Comparator.naturalOrder())
                .orElse(null);

        String categoryName = product.getCategoryId() == null ? null
                : categories.findById(product.getCategoryId())
                        .map(ProductCategory::getName).orElse(null);

        return new CatalogDtos.ProductResponse(
                product.getId(), product.getName(), product.getCategoryId(), categoryName,
                product.getDescription(), product.getBaseUnit(), product.getBasePackSize(),
                product.getImageUrl(), productAliases, purchasable.size(), lowest);
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
