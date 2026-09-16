package com.costonomy.mp.discovery.web.dto;

import com.costonomy.mp.discovery.domain.ExplanationCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /**
     * A ranked supplier offer. Doc 07 §8.
     *
     * <p>Everything a restaurant needs to compare commercially (§23A.13), plus the
     * reasons behind the ranking. Deliberately contains no commission figure and no
     * score component derived from one — guardrail 9.
     */
    public record RecommendedOffer(
            Long offerId,
            Long supplierSkuId,
            Long supplierStoreId,
            String supplierName,
            String storeName,
            String skuName,
            String brandName,
            BigDecimal packSize,
            String packUnit,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            /** Item value for the requested quantity, before GST. */
            BigDecimal itemTotal,
            BigDecimal gstAmount,
            /**
             * Item + GST. Delivery is not included: it is not quoted until a
             * provider is selected after Ready for Pickup (Phase 11), and showing a
             * guessed figure inside a total would be a made-up commercial value.
             */
            BigDecimal effectiveTotal,
            String availability,
            BigDecimal availableQuantity,
            boolean coversFullQuantity,
            Integer etaMinutes,
            BigDecimal distanceKm,
            Integer responseSlaSeconds,
            /** Why this ranked where it did. Only codes the data supports (doc 07 §5). */
            List<ExplanationCode> explanations,
            BigDecimal score,
            /** Per-component normalised scores, for operations to inspect. */
            Map<String, BigDecimal> scoreComponents,
            /** The SKU's own picture, falling back to the canonical product's. */
            String imageUrl,
            /** Null when nobody has rated this store. Absent stays absent (doc 07 §4). */
            BigDecimal averageRating,
            int ratingCount) {
    }

    /**
     * Ranked offers for one product against one outlet.
     *
     * @param unservedReason why the list is empty, when it is. §23A.15 requires an
     *                       unmet item to be shown and explained rather than
     *                       silently dropped.
     */
    public record ProductRecommendation(
            Long canonicalProductId,
            String productName,
            BigDecimal requestedQuantity,
            String unit,
            List<RecommendedOffer> offers,
            String unservedReason) {
    }

    public record SuggestionResponse(
            String term,
            String type,
            Long canonicalProductId,
            String categoryName) {
    }

    public record SupplierSearchResult(
            Long supplierStoreId,
            String supplierName,
            String storeName,
            String city,
            BigDecimal distanceKm,
            boolean serviceable,
            Integer productCount,
            /** Null when nobody has rated this store. Never zero standing in for that. */
            BigDecimal averageRating,
            int ratingCount,
            boolean openNow,
            String opensAt) {
    }

    /**
     * A page of suppliers, and how many a distance filter left out.
     *
     * <p>{@code beyondRadius} exists so the app can say "4 more deliver here" and
     * offer to widen, rather than presenting a filtered list as the whole truth.
     */
    public record SupplierSearchPage(
            List<SupplierSearchResult> suppliers,
            int beyondRadius) {
    }

    /**
     * One thing a restaurant can buy, from one supplier.
     *
     * <p>The row behind SKU search, a store's catalog and the supplier comparison.
     * It leads with the SKU because that is what is being bought — the pack, the
     * brand, the price — and carries the supplier as context rather than as the
     * headline.
     *
     * <p>No commission field, here or anywhere near ranking: guardrail 9.
     */
    public record StorefrontSku(
            Long offerId,
            Long supplierSkuId,
            String skuName,
            String brandName,
            BigDecimal packSize,
            String packUnit,
            BigDecimal sellingPrice,
            BigDecimal gstRate,
            String availability,
            BigDecimal availableQuantity,
            /** The SKU's own picture, falling back to the canonical product's. */
            String imageUrl,
            Long canonicalProductId,
            String canonicalProductName,
            Long supplierStoreId,
            String supplierName,
            String storeName,
            BigDecimal distanceKm,
            boolean openNow,
            String opensAt,
            Integer preparationMinutes,
            BigDecimal averageRating,
            int ratingCount) {
    }
}
