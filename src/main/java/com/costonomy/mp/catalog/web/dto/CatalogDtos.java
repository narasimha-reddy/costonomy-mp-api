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
            BigDecimal packSize,
            String packUnit,
            BigDecimal sellingPrice,
            BigDecimal gstRate,
            String availability,
            BigDecimal availableQuantity,
            Integer responseSlaSeconds,
            Integer preparationMinutes) {
    }

    // ── Supplier-side SKU management ─────────────────────────────────────

    public record CreateSkuRequest(
            @NotNull(message = "Choose the product this SKU is")
            Long canonicalProductId,
            @Size(max = 120) String skuCode,
            @NotBlank(message = "Enter the product name") @Size(max = 250) String name,
            @Size(max = 200) String brandName,
            @NotNull(message = "Enter the pack size")
            @DecimalMin(value = "0.0001", message = "Pack size must be greater than zero")
            BigDecimal packSize,
            @NotBlank(message = "Enter the pack unit") @Size(max = 32) String packUnit,
            @Size(max = 1000) String imageUrl,
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
            @DecimalMin(value = "0.0001") BigDecimal packSize,
            @Size(max = 32) String packUnit,
            @Size(max = 1000) String imageUrl,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @DecimalMin(value = "0.0000") BigDecimal sellingPrice,
            @DecimalMin(value = "0.0000") @DecimalMax(value = "100.0000") BigDecimal gstRate,
            @Pattern(regexp = "AVAILABLE|OUT_OF_STOCK") String availability,
            BigDecimal availableQuantity) {
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
            BigDecimal packSize,
            String packUnit,
            /**
             * This SKU's own picture — the supplier's pack, as they sell it.
             * <p>Usually null. Most suppliers never photograph their packs, which
             * is why {@link #canonicalProductImageUrl} exists beside it.
             */
            String imageUrl,
            /**
             * The canonical product's picture, so a listing has a face even when
             * the supplier has not given it one.
             * <p>Free to include — the product is already loaded for its name —
             * and it is what makes "the SKU's image, else the product's" a
             * decision the client can take without a second request. The two are
             * kept separate rather than merged server-side: a screen showing a
             * supplier what they have uploaded must be able to tell the
             * difference between their picture and the platform's.
             */
            String canonicalProductImageUrl,
            String status,
            BigDecimal sellingPrice,
            BigDecimal gstRate,
            String availability,
            BigDecimal availableQuantity,
            Instant priceEffectiveFrom) {
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
