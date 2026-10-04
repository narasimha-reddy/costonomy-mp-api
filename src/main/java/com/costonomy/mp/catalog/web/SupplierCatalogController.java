package com.costonomy.mp.catalog.web;

import com.costonomy.mp.catalog.service.CatalogImportService;
import com.costonomy.mp.catalog.service.SupplierCatalogService;
import com.costonomy.mp.catalog.web.dto.CatalogDtos;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** A supplier store's own catalog. Doc 04 §7. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Supplier catalog")
public class SupplierCatalogController {

    private final SupplierCatalogService supplierCatalog;
    private final CatalogImportService importService;

    @GetMapping("/supplier-stores/{storeId}/skus")
    @Operation(summary = "List a store's SKUs")
    public ApiResponse<List<CatalogDtos.SkuResponse>> listSkus(
            @PathVariable Long storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(supplierCatalog.listSkus(
                ActorContext.requireUserId(), storeId, status, page, size));
    }

    @PostMapping("/supplier-stores/{storeId}/skus")
    @Operation(
            summary = "Add a SKU",
            description = """
                    Maps one of the supplier's products onto a Mandi canonical product —
                    that mapping is what makes offers comparable, so an unmapped product
                    is rejected rather than invented.
                    """)
    public ApiResponse<CatalogDtos.SkuResponse> createSku(
            @PathVariable Long storeId,
            @Valid @RequestBody CatalogDtos.CreateSkuRequest request) {
        return ApiResponse.ok(supplierCatalog.createSku(
                ActorContext.requireUserId(), storeId, request));
    }

    @PatchMapping("/supplier-skus/{skuId}")
    @Operation(
            summary = "Update a SKU, its price or its availability",
            description = """
                    A change to price, GST or availability **supersedes** the current offer
                    rather than editing it: the previous figures stay on record so past
                    orders remain explicable and price history survives.

                    Changing only identity fields leaves the current offer untouched.
                    """)
    public ApiResponse<CatalogDtos.SkuResponse> updateSku(
            @PathVariable Long skuId,
            @Valid @RequestBody CatalogDtos.UpdateSkuRequest request) {
        return ApiResponse.ok(supplierCatalog.updateSku(
                ActorContext.requireUserId(), skuId, request));
    }

    @PutMapping("/supplier-skus/{skuId}/handling")
    @Operation(
            summary = "Declare how a SKU must be handled (cold chain, catch-weight)",
            description = """
                    Superseding, never an in-place edit: the current declaration is closed and a new one opened,
                    with who declared it and why, and the change is audited with before and after. Orders already
                    placed keep what they were placed with. A chilled product's SKU cannot be declared as not chilled.
                    """)
    public ApiResponse<CatalogDtos.SkuResponse> declareHandling(
            @PathVariable Long skuId,
            @Valid @RequestBody CatalogDtos.HandlingDeclarationRequest request) {
        return ApiResponse.ok(supplierCatalog.declareHandling(ActorContext.requireUserId(), skuId, request));
    }

    @GetMapping("/supplier-skus/{skuId}/price-history")
    @Operation(summary = "Price and availability history for a SKU")
    public ApiResponse<List<CatalogDtos.PriceHistoryEntry>> priceHistory(@PathVariable Long skuId) {
        return ApiResponse.ok(supplierCatalog.priceHistory(ActorContext.requireUserId(), skuId));
    }

    @GetMapping("/supplier-stores/{storeId}/products/{productId}/variants")
    @Operation(
            summary = "Get item variants and brand options",
            description = """
                    Unified Item Variant Manager: retrieves all brand and grade variants for
                    a canonical item under this store, sorted with lowest priced first, plus
                    top-selling preset recommendations.
                    """)
    public ApiResponse<CatalogDtos.ItemVariantGroupResponse> getItemVariants(
            @PathVariable Long storeId,
            @PathVariable Long productId) {
        return ApiResponse.ok(supplierCatalog.getItemVariants(
                ActorContext.requireUserId(), storeId, productId));
    }

    @PostMapping("/supplier-stores/{storeId}/products/{productId}/variants/batch")
    @Operation(
            summary = "Batch save or update variants under an item",
            description = """
                    Unified Item Variant Manager: batch creates or updates multiple brand & grade
                    variants with individual MRP, selling price, stock and pack size in one screen action.
                    """)
    public ApiResponse<CatalogDtos.ItemVariantGroupResponse> batchUpdateVariants(
            @PathVariable Long storeId,
            @PathVariable Long productId,
            @Valid @RequestBody CatalogDtos.BatchUpdateVariantsRequest request) {
        return ApiResponse.ok(supplierCatalog.batchUpdateVariants(
                ActorContext.requireUserId(), storeId, request));
    }

    // ── Bulk import ──────────────────────────────────────────────────────

    @PostMapping(value = "/supplier-stores/{storeId}/catalog/import", consumes = "multipart/form-data")
    @Operation(
            summary = "Upload a catalog file for validation",
            description = """
                    Accepts CSV or XLSX. Parses, maps columns, validates every row and
                    returns the full preview — **nothing is written to the catalog yet**.

                    Column headers are matched loosely, so "Selling Price", "price" and
                    "Rate" all work. Invalid rows come back with their line number, field
                    and reason; they are skipped rather than guessed at.

                    Call `confirm` to apply.
                    """)
    public ApiResponse<CatalogDtos.ImportSummaryResponse> upload(
            @PathVariable Long storeId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.upload(ActorContext.requireUserId(), storeId, file));
    }

    @PostMapping("/catalog/imports/{importId}/confirm")
    @Operation(
            summary = "Apply a validated import",
            description = """
                    Imports every valid row in one transaction — all of them land or none
                    do. Rows matching an existing SKU code update it; the rest are created.
                    Invalid rows are skipped and reported.
                    """)
    public ApiResponse<CatalogDtos.ImportSummaryResponse> confirm(@PathVariable Long importId) {
        return ApiResponse.ok(importService.confirm(ActorContext.requireUserId(), importId));
    }

    @GetMapping("/catalog/imports/{importId}")
    @Operation(summary = "Import preview or summary, with every row's outcome")
    public ApiResponse<CatalogDtos.ImportSummaryResponse> importSummary(@PathVariable Long importId) {
        return ApiResponse.ok(importService.summary(ActorContext.requireUserId(), importId));
    }

    @PostMapping("/catalog/imports/{importId}/cancel")
    @Operation(summary = "Discard a validated import without applying it")
    public ApiResponse<Void> cancelImport(@PathVariable Long importId) {
        importService.cancel(ActorContext.requireUserId(), importId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/supplier-stores/{storeId}/catalog/imports")
    @Operation(summary = "Import history for a store")
    public ApiResponse<List<CatalogDtos.ImportSummaryResponse>> importHistory(
            @PathVariable Long storeId) {
        return ApiResponse.ok(importService.history(ActorContext.requireUserId(), storeId));
    }

    @GetMapping("/supplier-stores/{storeId}/rate-sheet")
    @Operation(
            summary = "Get daily morning rate sheet for fast repricing",
            description = "Returns all catalog SKUs for the store formatted as a spreadsheet-like pricing grid.")
    public ApiResponse<CatalogDtos.RateSheetResponse> getRateSheet(
            @PathVariable Long storeId) {
        return ApiResponse.ok(supplierCatalog.getRateSheet(ActorContext.requireUserId(), storeId));
    }

    @PostMapping("/supplier-stores/{storeId}/rate-sheet")
    @Operation(
            summary = "Batch reprice multiple SKUs in the morning rate sheet (60-second repricing)",
            description = "Atomically updates selling prices, MRPs, and availability for multiple items in under a minute.")
    public ApiResponse<CatalogDtos.UpdateRateSheetResponse> updateRateSheet(
            @PathVariable Long storeId,
            @Valid @RequestBody CatalogDtos.UpdateRateSheetRequest request) {
        return ApiResponse.ok(supplierCatalog.updateRateSheet(ActorContext.requireUserId(), storeId, request));
    }
}
