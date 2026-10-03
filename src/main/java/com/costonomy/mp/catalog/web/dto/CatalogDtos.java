package com.costonomy.mp.catalog.web.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CategoryResponse(
            Long id, Long parentId, String name, String slug, String imageUrl, Integer displayOrder) {
    }

    public record BrandResponse(Long id, String name) {
    }

    public record ProductResponse(
            Long id,
            String name,
            Long categoryId,
            String categoryName,
            String description,
            String baseUnit,
            BigDecimal basePackSize,
            String imageUrl,
            List<String> aliases,
            /** How many purchasable offers exist. Drives "3 suppliers" on a card (§23A.11). */
            Integer offerCount,
            /** The lowest current price, for the "from ₹X" line. Null when nothing is available. */
            BigDecimal lowestPrice) {
    }

    /**
     * A supplier's offer for a product, as the restaurant sees it (§23A.13).
     *
     * <p>Carries everything needed to compare commercially. Deliberately contains
     * no commission figure and no ranking score derived from one — guardrail 9.
     */
    public record OfferResponse(
            Long offerId,
            Long supplierSkuId,
            Long supplierStoreId,
            String supplierStoreName,
            String supplierName,
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
            Integer responseSlaSeconds,
            Integer preparationMinutes) {

        public OfferResponse(
                Long offerId, Long supplierSkuId, Long supplierStoreId,
                String supplierStoreName, String supplierName, String skuName,
                String brandName, BigDecimal packSize, String packUnit,
                BigDecimal sellingPrice, BigDecimal gstRate, String availability,
                BigDecimal availableQuantity, Integer responseSlaSeconds,
                Integer preparationMinutes) {
            this(offerId, supplierSkuId, supplierStoreId, supplierStoreName, supplierName,
                    skuName, brandName, null, packSize, packUnit, null, sellingPrice,
                    null, null, gstRate, availability, availableQuantity,
                    responseSlaSeconds, preparationMinutes);
        }
    }

    /**
     * The unit vocabulary, served rather than hard-coded in each client.
     *
     * <p>D-079 is the argument for this endpoint existing: a client that keeps its
     * own copy of a server vocabulary compiles perfectly while being wrong, and
     * nothing notices until a comparison silently stops matching. A list the app
     * fetches cannot drift from the enum that validates against it.
     *
     * @param requiresMeasure the pack units that must also state their contents
     * @param measureUnits    what those contents may be measured in
     */
    public record UnitsResponse(
            List<String> packUnits,
            List<String> requiresMeasure,
            List<String> measureUnits) {
    }

    // ── Supplier-side SKU management ─────────────────────────────────────

    /** One kitchen's verdict on a pack they received. D-096. */
    public record CreateSkuReviewRequest(
            @NotNull(message = "Choose a rating")
            @Min(value = 1, message = "A rating is between 1 and 5")
            @Max(value = 5, message = "A rating is between 1 and 5")
            Integer rating,
            @Size(max = 2000) String comment) {
    }

    public record SkuReviewResponse(
            Long id,
            Long supplierSkuId,
            Long supplierOrderItemId,
            Integer rating,
            String comment,
            /** Who, at outlet granularity. A person's name is not the point. */
            String outletName,
            Instant createdAt) {
    }

    public record CreateSkuRequest(
            @NotNull(message = "Choose the product this SKU is")
            Long canonicalProductId,
            @Size(max = 120) String skuCode,
            @NotBlank(message = "Enter the product name") @Size(max = 250) String name,
            @Size(max = 200) String brandName,
            @Size(max = 100) String grade,
            @NotNull(message = "Enter the pack size")
            @DecimalMin(value = "0.0001", message = "Pack size must be greater than zero")
            BigDecimal packSize,
            @NotBlank(message = "Enter the pack unit") @Size(max = 32) String packUnit,
            /**
             * What is inside one pack, when {@code packUnit} does not say.
             * <p>Required for PKT, CASE, BULK, TIN and BUNDLE — "1 PKT" is a
             * bundle, not an amount. Refused for the rest, because a SKU packed
             * in KG already states its amount once.
             */
            @DecimalMin(value = "0.0001", message = "Pack contents must be greater than zero")
            BigDecimal measureValue,
            @Size(max = 16) String measureUnit,
            @DecimalMin(value = "0.0000", message = "MRP can't be negative")
            BigDecimal mrp,
            @Size(max = 1000) String imageUrl,
            /**
             * Everything a kitchen decides on rather than compares on. D-096.
             *
             * <p>All optional. A listing without any of it behaves exactly as it
             * did before the detail page existed.
             */
            @Size(max = 2000) String description,
            /** Pack dimensions in centimetres. */
            @DecimalMin(value = "0.00") BigDecimal lengthCm,
            @DecimalMin(value = "0.00") BigDecimal widthCm,
            @DecimalMin(value = "0.00") BigDecimal heightCm,
            @DecimalMin(value = "0.00") BigDecimal weightGrams,
            /** A YouTube link. Pasted, never uploaded. */
            @Size(max = 500) String youtubeUrl,
            /**
             * The gallery, in order. `imageUrl` above stays the thumbnail.
             */
            List<@Size(max = 500) String> images,
            @NotNull(message = "Enter the price")
            @DecimalMin(value = "0.0000", message = "Price can't be negative")
            BigDecimal sellingPrice,
            @NotNull(message = "Enter the GST rate")
            @DecimalMin(value = "0.0000", message = "GST can't be negative")
            @DecimalMax(value = "100.0000", message = "GST can't exceed 100%")
            BigDecimal gstRate,
            @Pattern(regexp = "AVAILABLE|OUT_OF_STOCK",
                     message = "Availability must be AVAILABLE or OUT_OF_STOCK")
            String availability,
            BigDecimal availableQuantity) {

        public CreateSkuRequest(
                Long canonicalProductId, String skuCode, String name, String brandName,
                BigDecimal packSize, String packUnit, BigDecimal measureValue,
                String measureUnit, String imageUrl, String description,
                BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                BigDecimal weightGrams, String youtubeUrl, List<String> images,
                BigDecimal sellingPrice, BigDecimal gstRate, String availability,
                BigDecimal availableQuantity) {
            this(canonicalProductId, skuCode, name, brandName, null, packSize, packUnit,
                    measureValue, measureUnit, null, imageUrl, description, lengthCm,
                    widthCm, heightCm, weightGrams, youtubeUrl, images, sellingPrice,
                    gstRate, availability, availableQuantity);
        }
    }

    /**
     * Update a SKU. Identity fields and commercial fields both accepted; a change
     * to price, GST or availability supersedes the current offer rather than
     * editing it.
     */
    public record UpdateSkuRequest(
            Long canonicalProductId,
            @Size(max = 120) String skuCode,
            @Size(max = 250) String name,
            @Size(max = 200) String brandName,
            @Size(max = 100) String grade,
            @DecimalMin(value = "0.0001") BigDecimal packSize,
            @Size(max = 32) String packUnit,
            @DecimalMin(value = "0.0001") BigDecimal measureValue,
            @Size(max = 16) String measureUnit,
            @DecimalMin(value = "0.0000") BigDecimal mrp,
            @Size(max = 1000) String imageUrl,
            /**
             * Everything a kitchen decides on rather than compares on. D-096.
             *
             * <p>All optional. A listing without any of it behaves exactly as it
             * did before the detail page existed.
             */
            @Size(max = 2000) String description,
            /** Pack dimensions in centimetres. */
            @DecimalMin(value = "0.00") BigDecimal lengthCm,
            @DecimalMin(value = "0.00") BigDecimal widthCm,
            @DecimalMin(value = "0.00") BigDecimal heightCm,
            @DecimalMin(value = "0.00") BigDecimal weightGrams,
            /** A YouTube link. Pasted, never uploaded. */
            @Size(max = 500) String youtubeUrl,
            /**
             * The gallery, in order. `imageUrl` above stays the thumbnail.
             */
            List<@Size(max = 500) String> images,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @DecimalMin(value = "0.0000") BigDecimal sellingPrice,
            @DecimalMin(value = "0.0000") @DecimalMax(value = "100.0000") BigDecimal gstRate,
            @Pattern(regexp = "AVAILABLE|OUT_OF_STOCK") String availability,
            BigDecimal availableQuantity) {

        public UpdateSkuRequest(
                Long canonicalProductId, String skuCode, String name, String brandName,
                BigDecimal packSize, String packUnit, BigDecimal measureValue,
                String measureUnit, String imageUrl, String description,
                BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                BigDecimal weightGrams, String youtubeUrl, List<String> images,
                String status, BigDecimal sellingPrice, BigDecimal gstRate,
                String availability, BigDecimal availableQuantity) {
            this(canonicalProductId, skuCode, name, brandName, null, packSize, packUnit,
                    measureValue, measureUnit, null, imageUrl, description, lengthCm,
                    widthCm, heightCm, weightGrams, youtubeUrl, images, status,
                    sellingPrice, gstRate, availability, availableQuantity);
        }
    }

    public record SkuResponse(
            Long id,
            Long supplierStoreId,
            Long canonicalProductId,
            String canonicalProductName,
            /**
             * The canonical product's category.
             *
             * <p>Free to include — the product is already loaded to get its name —
             * and without it a supplier's own catalog cannot be grouped or filtered
             * by category at all. The name is deliberately not repeated here: a
             * client that needs it already holds the category list.
             */
            Long categoryId,
            String skuCode,
            String name,
            String brandName,
            String grade,
            BigDecimal packSize,
            String packUnit,
            /** What is inside one pack, or null when the pack unit already says. */
            BigDecimal measureValue,
            String measureUnit,
            /**
             * This SKU's own picture — the supplier's pack, as they sell it.
             * <p>Usually null. Most suppliers never photograph their packs, which
             * is why {@link #canonicalProductImageUrl} exists beside it.
             */
            String imageUrl,
            /**
             * The canonical product's picture, so a listing has a face even when
             * the supplier has not given it one.
             */
            String canonicalProductImageUrl,
            /** D-096. Null or empty when the supplier has not filled them in. */
            String description,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            BigDecimal weightGrams,
            String youtubeUrl,
            List<String> images,
            String status,
            BigDecimal mrp,
            BigDecimal sellingPrice,
            BigDecimal discountAmount,
            Integer discountPercent,
            BigDecimal gstRate,
            String availability,
            BigDecimal availableQuantity,
            Instant priceEffectiveFrom) {

        public SkuResponse(
                Long id, Long supplierStoreId, Long canonicalProductId,
                String canonicalProductName, Long categoryId, String skuCode,
                String name, String brandName, BigDecimal packSize, String packUnit,
                BigDecimal measureValue, String measureUnit, String imageUrl,
                String canonicalProductImageUrl, String description,
                BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                BigDecimal weightGrams, String youtubeUrl, List<String> images,
                String status, BigDecimal sellingPrice, BigDecimal gstRate,
                String availability, BigDecimal availableQuantity,
                Instant priceEffectiveFrom) {
            this(id, supplierStoreId, canonicalProductId, canonicalProductName, categoryId,
                    skuCode, name, brandName, null, packSize, packUnit, measureValue,
                    measureUnit, imageUrl, canonicalProductImageUrl, description, lengthCm,
                    widthCm, heightCm, weightGrams, youtubeUrl, images, status,
                    null, sellingPrice, null, null, gstRate, availability,
                    availableQuantity, priceEffectiveFrom);
        }
    }

    /**
     * Preset suggestion for a top-selling brand/grade combination under a product.
     */
    public record VariantPreset(
            String brandName,
            String grade,
            BigDecimal packSize,
            String packUnit,
            BigDecimal typicalMrp,
            boolean isTopSeller) {
    }

    /**
     * Item-Centric Variant Management group for a supplier store.
     * Select an item (e.g. Paneer) and manage all its brand and grade variants with
     * combination of top selling items first.
     */
    public record ItemVariantGroupResponse(
            Long canonicalProductId,
            String productName,
            Long categoryId,
            String categoryName,
            String imageUrl,
            String baseUnit,
            List<SkuResponse> variants,
            List<VariantPreset> recommendedPresets) {
    }

    /**
     * Single variant entry for batch creation or updating on the Item Variant Manager screen.
     */
    public record BatchVariantEntry(
            Long skuId,
            String skuCode,
            String name,
            String brandName,
            String grade,
            BigDecimal packSize,
            String packUnit,
            BigDecimal mrp,
            BigDecimal sellingPrice,
            BigDecimal gstRate,
            String availability,
            BigDecimal availableQuantity) {
    }

    public record BatchUpdateVariantsRequest(
            @NotNull(message = "canonicalProductId is required")
            Long canonicalProductId,
            @NotEmpty(message = "At least one variant must be provided")
            List<BatchVariantEntry> variants) {
    }

    public record PriceHistoryEntry(
            BigDecimal sellingPrice,
            BigDecimal gstRate,
            String availability,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }

    // ── Bulk import ──────────────────────────────────────────────────────

    public record ImportSummaryResponse(
            Long importId,
            String fileName,
            String status,
            Integer totalRows,
            Integer validRows,
            Integer invalidRows,
            Integer importedRows,
            Integer createdSkus,
            Integer updatedSkus,
            String errorMessage,
            Instant completedAt,
            /** Every row with its outcome — the Preview and Summary steps read this. */
            List<ImportRowResponse> rows) {
    }

    public record ImportRowResponse(
            Integer rowNumber,
            String status,
            String skuCode,
            String productName,
            BigDecimal sellingPrice,
            List<RowError> errors) {
    }

    /** One problem with one field of one row. Doc 40 requires this granularity. */
    public record RowError(String field, String code, String message) {
    }
}
