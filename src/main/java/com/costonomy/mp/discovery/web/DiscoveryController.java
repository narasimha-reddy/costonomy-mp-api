package com.costonomy.mp.discovery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.discovery.service.RecommendationService;
import com.costonomy.mp.discovery.service.SearchService;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Search, suggestions and Best Value recommendations. Doc 04 §8, doc 07. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Discovery")
public class DiscoveryController {

    private final RecommendationService recommendations;
    private final SearchService search;

    @GetMapping("/search/suggestions")
    @Operation(
            summary = "Type-ahead suggestions",
            description = """
                    Products, their configured aliases, and categories. The suggestion shows
                    the term that matched — typing "dah" surfaces "Dahi", which is what the
                    kitchen expects even though the product is called Curd.

                    Needs at least two characters.
                    """)
    public ApiResponse<List<DiscoveryDtos.SuggestionResponse>> suggestions(
            @RequestParam("q") String query) {
        return ApiResponse.ok(search.suggest(query));
    }

    @GetMapping("/search/suppliers")
    @Operation(
            summary = "Search suppliers by name",
            description = """
                    Secondary to product search by design (doc 01 §25) — a restaurant
                    looks for paneer, not for a paneer supplier. Only suppliers that can
                    currently trade are returned. Pass `outletId` to get distance and
                    whether each store actually delivers there.
                    """)
    public ApiResponse<List<DiscoveryDtos.SupplierSearchResult>> suppliers(
            @RequestParam("q") String query,
            @RequestParam(required = false) Long outletId) {
        return ApiResponse.ok(search.searchSuppliers(query, outletId));
    }

    @GetMapping("/products/{id}/recommendations")
    @Operation(
            summary = "Best Value ranked offers for a product at an outlet",
            description = """
                    The supplier comparison behind §23A.13. Filters to offers that could
                    actually be bought — available, active SKU, tradeable store, delivers to
                    this outlet — then ranks by Best Value: price, ETA, availability, and
                    the supplier's fill rate, on-time record, rating and reliability.

                    Each offer carries `explanations` saying why it ranked where it did.
                    An explanation is only ever returned when the calculated data supports
                    it (doc 07 §5); a supplier without enough order history is marked
                    `NEW_SUPPLIER` rather than being given credit it has not earned.

                    **Commission is not a ranking input, positively or negatively.**

                    When nothing can serve the outlet the list is empty and
                    `unservedReason` explains why, so the requirement can be shown as
                    unmet rather than silently disappearing (§23A.15).

                    `effectiveTotal` is item value plus GST. Delivery is not included —
                    it is not quoted until a provider is selected after Ready for Pickup.
                    """)
    public ApiResponse<DiscoveryDtos.ProductRecommendation> recommendations(
            @PathVariable Long id,
            @RequestParam Long outletId,
            @RequestParam(required = false) BigDecimal quantity) {
        return ApiResponse.ok(recommendations.recommendForProduct(
                ActorContext.requireUserId(), id, outletId, quantity));
    }
}
