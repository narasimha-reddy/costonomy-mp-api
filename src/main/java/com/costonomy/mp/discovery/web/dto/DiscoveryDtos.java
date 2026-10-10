package com.costonomy.mp.discovery.web.dto;

import com.costonomy.mp.discovery.domain.ExplanationCode;
import com.costonomy.mp.procurement.domain.Pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /**
     * A brand option / variant for an item from a supplier.
     * When an item is fulfilled by multiple brands, all options are listed with lowest priced first.
     */
    public record BrandOption(
            Long supplierSkuId,
            Long offerId,
            String skuName,
            String brandName,
            String grade,
            BigDecimal packSize,
            String packUnit,
            BigDecimal mrp,
            BigDecimal sellingPrice,
            BigDecimal discountAmount,
            Integer discountPercent,
            BigDecimal gstRate,
            BigDecimal unitPriceInclusiveGst,
            String imageUrl,
            String availability,
            BigDecimal availableQuantity,
            BigDecimal measureValue,
            String measureUnit) {

        public BrandOption(
                Long supplierSkuId, Long offerId, String skuName, String brandName,
                BigDecimal packSize, String packUnit, BigDecimal sellingPrice,
                BigDecimal gstRate, BigDecimal unitPriceInclusiveGst, String imageUrl,
                String availability, BigDecimal availableQuantity, BigDecimal measureValue,
                String measureUnit) {
            this(supplierSkuId, offerId, skuName, brandName, null, packSize, packUnit,
                    null, sellingPrice, null, null, gstRate, unitPriceInclusiveGst,
                    imageUrl, availability, availableQuantity, measureValue, measureUnit);
        }
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
            String grade,
            BigDecimal packSize,
            String packUnit,
            BigDecimal mrp,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            Integer discountPercent,
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
            int ratingCount,
            /**
             * One pack, with GST — what a restaurant actually pays for it.
             */
            BigDecimal unitPriceInclusiveGst,
            /**
             * What one base unit costs, with GST — "₹404.67 per KG".
             */
            BigDecimal pricePerBaseUnit,
            /**
             * How many other packs of this product the same store lists. D-096.
             */
            int otherPackCount,
            /**
             * All brand options from this supplier for this item, sorted with lowest priced first.
             */
            List<BrandOption> brandOptions) {

        public RecommendedOffer(
                Long offerId, Long supplierSkuId, Long supplierStoreId, String supplierName,
                String storeName, String skuName, String brandName, BigDecimal packSize,
                String packUnit, BigDecimal unitPrice, BigDecimal gstRate, BigDecimal itemTotal,
                BigDecimal gstAmount, BigDecimal effectiveTotal, String availability,
                BigDecimal availableQuantity, boolean coversFullQuantity, Integer etaMinutes,
                BigDecimal distanceKm, Integer responseSlaSeconds,
                List<ExplanationCode> explanations, BigDecimal score,
                Map<String, BigDecimal> scoreComponents, String imageUrl,
                BigDecimal averageRating, int ratingCount, BigDecimal unitPriceInclusiveGst,
                BigDecimal pricePerBaseUnit, int otherPackCount) {
            this(offerId, supplierSkuId, supplierStoreId, supplierName, storeName, skuName,
                    brandName, null, packSize, packUnit, null, unitPrice, null, null,
                    gstRate, itemTotal, gstAmount, effectiveTotal, availability,
                    availableQuantity, coversFullQuantity, etaMinutes, distanceKm,
                    responseSlaSeconds, explanations, score, scoreComponents, imageUrl,
                    averageRating, ratingCount, unitPriceInclusiveGst, pricePerBaseUnit,
                    otherPackCount, List.of());
        }

        public RecommendedOffer(
                Long offerId, Long supplierSkuId, Long supplierStoreId, String supplierName,
                String storeName, String skuName, String brandName, BigDecimal packSize,
                String packUnit, BigDecimal unitPrice, BigDecimal gstRate, BigDecimal itemTotal,
                BigDecimal gstAmount, BigDecimal effectiveTotal, String availability,
                BigDecimal availableQuantity, boolean coversFullQuantity, Integer etaMinutes,
                BigDecimal distanceKm, Integer responseSlaSeconds,
                List<ExplanationCode> explanations, BigDecimal score,
                Map<String, BigDecimal> scoreComponents, String imageUrl,
                BigDecimal averageRating, int ratingCount, BigDecimal unitPriceInclusiveGst,
                BigDecimal pricePerBaseUnit, int otherPackCount, List<BrandOption> brandOptions) {
            this(offerId, supplierSkuId, supplierStoreId, supplierName, storeName, skuName,
                    brandName, null, packSize, packUnit, null, unitPrice, null, null,
                    gstRate, itemTotal, gstAmount, effectiveTotal, availability,
                    availableQuantity, coversFullQuantity, etaMinutes, distanceKm,
                    responseSlaSeconds, explanations, score, scoreComponents, imageUrl,
                    averageRating, ratingCount, unitPriceInclusiveGst, pricePerBaseUnit,
                    otherPackCount, brandOptions);
        }
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
            String unservedReason,
            /**
             * Suppliers that could serve this outlet but were left out by the filters the caller passed (D-183).
             * Lets the screen say "3 more hidden by your filters" instead of showing a list that shrank for no
             * visible reason. Zero when no filter was passed.
             */
            int hiddenByFilters) {

        /** A recommendation with no filter applied. */
        public ProductRecommendation(Long canonicalProductId, String productName, BigDecimal requestedQuantity,
                                     String unit, List<RecommendedOffer> offers, String unservedReason) {
            this(canonicalProductId, productName, requestedQuantity, unit, offers, unservedReason, 0);
        }
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
            /**
             * How many of this store's buyable items matched the search term.
             *
             * <p>Zero when there was no term, and zero for a store that matched on
             * its name alone. It is what lets a row say why it is in the list —
             * "stocks 4 matching items" rather than leaving a restaurant to guess.
             */
            int matchingProductCount,
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
            int beyondRadius,
            int total,
            Integer nextOffset) {

        public SupplierSearchPage(List<SupplierSearchResult> suppliers, int beyondRadius) {
            this(suppliers, beyondRadius, suppliers.size(), null);
        }
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
            String grade,
            BigDecimal packSize,
            String packUnit,
            BigDecimal mrp,
            BigDecimal sellingPrice,
            BigDecimal discountAmount,
            Integer discountPercent,
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
            int ratingCount,
            /**
             * What aisle this belongs in, for a storefront that groups by it.
             */
            Long categoryId,
            String categoryName,
            /** The amount inside one pack, where a pack has one. */
            BigDecimal measureValue,
            String measureUnit,
            /**
             * All brand options from this supplier for this item, sorted with lowest priced first.
             */
            List<BrandOption> brandOptions) {

        public StorefrontSku(
                Long offerId, Long supplierSkuId, String skuName, String brandName,
                BigDecimal packSize, String packUnit, BigDecimal sellingPrice,
                BigDecimal gstRate, String availability, BigDecimal availableQuantity,
                String imageUrl, Long canonicalProductId, String canonicalProductName,
                Long supplierStoreId, String supplierName, String storeName,
                BigDecimal distanceKm, boolean openNow, String opensAt,
                Integer preparationMinutes, BigDecimal averageRating, int ratingCount,
                Long categoryId, String categoryName, BigDecimal measureValue,
                String measureUnit) {
            this(offerId, supplierSkuId, skuName, brandName, null, packSize, packUnit,
                    null, sellingPrice, null, null, gstRate, availability,
                    availableQuantity, imageUrl, canonicalProductId, canonicalProductName,
                    supplierStoreId, supplierName, storeName, distanceKm, openNow,
                    opensAt, preparationMinutes, averageRating, ratingCount, categoryId,
                    categoryName, measureValue, measureUnit, List.of());
        }

        public StorefrontSku(
                Long offerId, Long supplierSkuId, String skuName, String brandName,
                BigDecimal packSize, String packUnit, BigDecimal sellingPrice,
                BigDecimal gstRate, String availability, BigDecimal availableQuantity,
                String imageUrl, Long canonicalProductId, String canonicalProductName,
                Long supplierStoreId, String supplierName, String storeName,
                BigDecimal distanceKm, boolean openNow, String opensAt,
                Integer preparationMinutes, BigDecimal averageRating, int ratingCount,
                Long categoryId, String categoryName, BigDecimal measureValue,
                String measureUnit, List<BrandOption> brandOptions) {
            this(offerId, supplierSkuId, skuName, brandName, null, packSize, packUnit,
                    null, sellingPrice, null, null, gstRate, availability,
                    availableQuantity, imageUrl, canonicalProductId, canonicalProductName,
                    supplierStoreId, supplierName, storeName, distanceKm, openNow,
                    opensAt, preparationMinutes, averageRating, ratingCount, categoryId,
                    categoryName, measureValue, measureUnit, brandOptions);
        }
    }

    /**
     * A supplier worth putting in front of a kitchen, and what they stock.
     *
     * <p>The categories are the point. "Metro Fresh Supplies, 5 km away" says
     * nothing about whether they are worth opening; "Dairy, Vegetables, Staples"
     * is the whole decision, and it is a fact about their catalogue rather than
     * anything they wrote about themselves.
     */
    public record PopularSupplier(
            Long supplierStoreId,
            String supplierName,
            String storeName,
            String locality,
            String city,
            BigDecimal distanceKm,
            BigDecimal averageRating,
            int ratingCount,
            /** How much they list, purchasable today. */
            int skuCount,
            boolean openNow,
            /** Orders can be placed here without sending a request first. D-094. */
            boolean directOrdersEnabled,
            List<SupplierCategory> categories) {
    }

    /**
     * The head of one supplier's shelf, for the restaurant standing in front of it.
     *
     * <p>Everything a kitchen needs before deciding to shop here, in one request:
     * who they are, whether this is even the right branch, how long it is likely
     * to take, what other kitchens thought, and — the one that decides whether
     * they can buy at all — whether this supplier has extended them credit.
     *
     * <p>Separate from the catalog rows because it is a different question with a
     * different lifetime. The rows change when a price does; this changes when an
     * agreement is approved or a branch opens.
     */
    public record StorefrontHeader(
            Long supplierStoreId,
            /** The branch. This is the title — it is where the goods come from. */
            String storeName,
            /** The organisation behind it, shown beneath the branch. */
            String supplierName,
            String city,
            BigDecimal distanceKm,
            boolean openNow,
            String opensAt,
            /**
             * Preparation plus travel, in minutes. Null when either end has no
             * coordinates — absent rather than guessed, because a delivery time
             * invented from a pincode is the kind of number people plan around.
             */
            Integer etaMinutes,
            /** Null when nobody has rated this store. Never zero standing in for that. */
            BigDecimal averageRating,
            int ratingCount,
            int skuCount,
            /**
             * Orders can be placed here without sending a request first. D-094.
             *
             * <p>A store that keeps stock has already answered the question the
             * request exists to ask, so the kitchen can go straight to an order.
             */
            boolean directOrdersEnabled,
            /** Null when this supplier has extended this outlet no credit. */
            StoreCredit credit,
            /**
             * This supplier's other branches, nearest first.
             *
             * <p>A supplier with two branches is two shelves, two distances and
             * two sets of prices, and a kitchen that arrived at the far one has
             * no way to discover the near one from inside the catalog.
             */
            List<SiblingStore> otherStores) {
    }

    /**
     * What this supplier has extended this outlet.
     *
     * <p>Every figure is the server's. {@code available} in particular is never
     * derived by a client (§23A.24) — what is left to spend depends on
     * reservations against orders in flight, which the app cannot see.
     *
     * <p>No {@code due} or {@code overdue}: those are an invoice question, and
     * answering it here would put a second implementation of the ageing rules
     * next to the one on the credit screen. A shelf header says what is left to
     * spend; what is owed is the credit screen's subject.
     */
    public record StoreCredit(
            Long agreementId,
            String status,
            BigDecimal approvedLimit,
            BigDecimal utilized,
            BigDecimal reserved,
            BigDecimal available,
            Integer creditPeriodDays,
            /** Whether an order can actually be funded on it right now. */
            boolean canFund) {
    }

    /** Another branch of the same supplier. */
    public record SiblingStore(
            Long supplierStoreId,
            String storeName,
            String city,
            BigDecimal distanceKm,
            boolean openNow) {
    }

    /**
     * One pack, in full — the page a kitchen decides on. D-096.
     *
     * <p>Everything the shelf row shows, plus what it leaves out: what the pack
     * actually is, what it measures, what it looks like from more than one
     * angle, and what other kitchens made of it.
     *
     * <p>The commercial half is the same shape the shelf uses, deliberately: a
     * detail page that priced a SKU differently from the row that led to it
     * would be the worst possible place to disagree.
     */
    public record SkuDetail(
            Long supplierSkuId,
            Long offerId,
            String skuName,
            String brandName,
            String grade,
            BigDecimal packSize,
            String packUnit,
            BigDecimal measureValue,
            String measureUnit,
            BigDecimal mrp,
            BigDecimal sellingPrice,
            BigDecimal discountAmount,
            Integer discountPercent,
            BigDecimal gstRate,
            BigDecimal unitPriceInclusiveGst,
            String availability,
            BigDecimal availableQuantity,
            /** The thumbnail, then the gallery. Never empty when either exists. */
            String imageUrl,
            List<String> images,
            String youtubeUrl,
            String description,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            BigDecimal weightGrams,
            Long canonicalProductId,
            String canonicalProductName,
            Long categoryId,
            String categoryName,
            Long supplierStoreId,
            String storeName,
            String supplierName,
            BigDecimal distanceKm,
            boolean openNow,
            String opensAt,
            Integer etaMinutes,
            /** This store's rating, which is about the store, not this pack. */
            BigDecimal storeRating,
            int storeRatingCount,
            /** This pack's own rating. Null when nobody has reviewed it. */
            BigDecimal averageRating,
            int reviewCount,
            List<SkuReviewResponse> reviews,
            /**
             * Other packs of the same product from this same store.
             */
            List<SkuSibling> otherPacks,
            /**
             * All brand options from this supplier for this item, sorted with lowest priced first.
             */
            List<BrandOption> brandOptions) {

        public SkuDetail(
                Long supplierSkuId, Long offerId, String skuName, String brandName,
                BigDecimal packSize, String packUnit, BigDecimal measureValue,
                String measureUnit, BigDecimal sellingPrice, BigDecimal gstRate,
                BigDecimal unitPriceInclusiveGst, String availability,
                BigDecimal availableQuantity, String imageUrl, List<String> images,
                String youtubeUrl, String description, BigDecimal lengthCm,
                BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightGrams,
                Long canonicalProductId, String canonicalProductName, Long categoryId,
                String categoryName, Long supplierStoreId, String storeName,
                String supplierName, BigDecimal distanceKm, boolean openNow,
                String opensAt, Integer etaMinutes, BigDecimal storeRating,
                int storeRatingCount, BigDecimal averageRating, int reviewCount,
                List<SkuReviewResponse> reviews, List<SkuSibling> otherPacks) {
            this(supplierSkuId, offerId, skuName, brandName, null, packSize, packUnit,
                    measureValue, measureUnit, null, sellingPrice, null, null, gstRate,
                    unitPriceInclusiveGst, availability, availableQuantity, imageUrl,
                    images, youtubeUrl, description, lengthCm, widthCm, heightCm,
                    weightGrams, canonicalProductId, canonicalProductName, categoryId,
                    categoryName, supplierStoreId, storeName, supplierName, distanceKm,
                    openNow, opensAt, etaMinutes, storeRating, storeRatingCount,
                    averageRating, reviewCount, reviews, otherPacks, List.of());
        }

        public SkuDetail(
                Long supplierSkuId, Long offerId, String skuName, String brandName,
                BigDecimal packSize, String packUnit, BigDecimal measureValue,
                String measureUnit, BigDecimal sellingPrice, BigDecimal gstRate,
                BigDecimal unitPriceInclusiveGst, String availability,
                BigDecimal availableQuantity, String imageUrl, List<String> images,
                String youtubeUrl, String description, BigDecimal lengthCm,
                BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightGrams,
                Long canonicalProductId, String canonicalProductName, Long categoryId,
                String categoryName, Long supplierStoreId, String storeName,
                String supplierName, BigDecimal distanceKm, boolean openNow,
                String opensAt, Integer etaMinutes, BigDecimal storeRating,
                int storeRatingCount, BigDecimal averageRating, int reviewCount,
                List<SkuReviewResponse> reviews, List<SkuSibling> otherPacks,
                List<BrandOption> brandOptions) {
            this(supplierSkuId, offerId, skuName, brandName, null, packSize, packUnit,
                    measureValue, measureUnit, null, sellingPrice, null, null, gstRate,
                    unitPriceInclusiveGst, availability, availableQuantity, imageUrl,
                    images, youtubeUrl, description, lengthCm, widthCm, heightCm,
                    weightGrams, canonicalProductId, canonicalProductName, categoryId,
                    categoryName, supplierStoreId, storeName, supplierName, distanceKm,
                    openNow, opensAt, etaMinutes, storeRating, storeRatingCount,
                    averageRating, reviewCount, reviews, otherPacks, brandOptions);
        }
    }

    /** One kitchen's verdict on a pack. */
    public record SkuReviewResponse(
            Long id,
            Integer rating,
            String comment,
            /** Who, at outlet granularity. A person's name is not the point. */
            String outletName,
            Instant createdAt) {
    }

    /** Another pack of the same product from the same store. */
    public record SkuSibling(
            Long supplierSkuId,
            String skuName,
            BigDecimal packSize,
            String packUnit,
            BigDecimal mrp,
            BigDecimal sellingPrice,
            BigDecimal discountAmount,
            Integer discountPercent,
            String imageUrl,
            String availability,
            String brandName,
            String grade,
            BigDecimal gstRate,
            BigDecimal unitPriceInclusiveGst,
            Long offerId) {

        public SkuSibling(
                Long supplierSkuId, String skuName, BigDecimal packSize,
                String packUnit, BigDecimal sellingPrice, String imageUrl,
                String availability) {
            this(supplierSkuId, skuName, packSize, packUnit, null, sellingPrice,
                    null, null, imageUrl, availability, null, null, null, null, null);
        }

        public SkuSibling(
                Long supplierSkuId, String skuName, BigDecimal packSize,
                String packUnit, BigDecimal sellingPrice, String imageUrl,
                String availability, String brandName, BigDecimal gstRate,
                BigDecimal unitPriceInclusiveGst, Long offerId) {
            this(supplierSkuId, skuName, packSize, packUnit, null, sellingPrice,
                    null, null, imageUrl, availability, brandName, null, gstRate,
                    unitPriceInclusiveGst, offerId);
        }
    }

    /** One aisle a supplier stocks, and how deep it goes. */
    public record SupplierCategory(
            Long categoryId,
            String name,
            int skuCount) {
    }
}
