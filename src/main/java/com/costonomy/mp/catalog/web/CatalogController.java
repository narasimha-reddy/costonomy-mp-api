package com.costonomy.mp.catalog.web;

import com.costonomy.mp.catalog.service.CatalogQueryService;
import com.costonomy.mp.catalog.web.dto.CatalogDtos;
import com.costonomy.mp.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The canonical catalog, as a restaurant browses it. Doc 04 §4. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Catalog")
public class CatalogController {

    private final CatalogQueryService catalog;

    @GetMapping("/categories")
    @Operation(summary = "Product categories")
    public ApiResponse<List<CatalogDtos.CategoryResponse>> categories() {
        return ApiResponse.ok(catalog.listCategories());
    }

    @GetMapping("/brands")
    @Operation(summary = "Brands")
    public ApiResponse<List<CatalogDtos.BrandResponse>> brands() {
        return ApiResponse.ok(catalog.listBrands());
    }

    @GetMapping("/products")
    @Operation(
            summary = "Browse canonical products",
            description = "Each result carries its offer count and lowest current price, "
                    + "so a product card renders without a second call.")
    public ApiResponse<List<CatalogDtos.ProductResponse>> products(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(catalog.listProducts(categoryId, page, size));
    }

    @GetMapping("/search/products")
    @Operation(
            summary = "Search products by name or alias",
            description = """
                    Product-first search (§23A.11). Matches the product's name and its
                    configured aliases — "dahi" finds Curd because that alias exists,
                    not because the matcher guessed.
                    """)
    public ApiResponse<List<CatalogDtos.ProductResponse>> search(@RequestParam("q") String query) {
        return ApiResponse.ok(catalog.searchProducts(query));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Get a product")
    public ApiResponse<CatalogDtos.ProductResponse> product(@PathVariable Long id) {
        return ApiResponse.ok(catalog.getProduct(id));
    }

    @GetMapping("/products/{id}/offers")
    @Operation(
            summary = "Supplier offers for a product",
            description = """
                    The supplier comparison (§23A.13). Offers from offline or suspended
                    suppliers are excluded — they could not accept an order anyway.

                    Ordered cheapest-first as a deterministic baseline. Best Value ranking
                    arrives in Phase 6. Commission never influences either ordering.
                    """)
    public ApiResponse<List<CatalogDtos.OfferResponse>> offers(@PathVariable Long id) {
        return ApiResponse.ok(catalog.offersForProduct(id));
    }
}
