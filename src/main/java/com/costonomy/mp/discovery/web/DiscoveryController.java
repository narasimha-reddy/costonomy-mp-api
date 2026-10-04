package com.costonomy.mp.discovery.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.discovery.service.PopularSupplierService;
import com.costonomy.mp.discovery.service.RecommendationService;
import com.costonomy.mp.discovery.service.SearchService;
import com.costonomy.mp.discovery.service.SkuDetailService;
import com.costonomy.mp.discovery.service.StorefrontService;
import com.costonomy.mp.discovery.service.SupplierStorefrontService;
import com.costonomy.mp.discovery.web.dto.DiscoveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    private final StorefrontService storefront;
    private final PopularSupplierService popular;
    private final SupplierStorefrontService storefronts;
    private final SkuDetailService skuDetails;
    private final AccessControlService accessControl;

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
            summary = "Suppliers that deliver to an outlet, nearest first",
            description = """
                    With `q` this is a search by name; without it, the directory of who
                    can deliver here — which is what a restaurant browsing for a supplier
                    actually wants, and what this endpoint could not previously answer.

                    Membership is the store's **own** declared radius or pincode list, so
                    a supplier who says they deliver 15km is not hidden by someone else's
                    idea of near. `radiusKm` narrows that; anything it excludes is counted
                    in `beyondRadius` rather than disappearing.

                    Only stores that can currently trade are returned.
                    """)
    public ApiResponse<DiscoveryDtos.SupplierSearchPage> suppliers(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(required = false) Long outletId,
            @RequestParam(required = false) BigDecimal radiusKm,
            @RequestParam(required = false) String reach) {
        if (outletId != null) {
            accessControl.requireScoped(ActorContext.requireUserId(),
                    Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        }
        return ApiResponse.ok(storefront.searchSuppliers(query, outletId, radiusKm, reach));
    }

    @GetMapping("/search/skus")
    @Operation(
            summary = "Search supplier SKUs directly",
            description = """
                    The other half of product search. `/search/products` answers "what is
                    curd" — one row per canonical product; this answers "what curd can I
                    buy right now", one row per supplier's pack, with its price and
                    picture.

                    Matches the supplier's SKU name, the brand and the canonical product,
                    because a kitchen types what it calls the thing. Only offers that are
                    purchasable from a store serving this outlet are returned.

                    Needs at least two characters.
                    """)
    public ApiResponse<List<DiscoveryDtos.StorefrontSku>> skus(
            @RequestParam("q") String query,
            @RequestParam(required = false) Long outletId,
            @RequestParam(required = false) Integer limit) {
        if (outletId != null) {
            accessControl.requireScoped(ActorContext.requireUserId(),
                    Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        }
        return ApiResponse.ok(storefront.searchSkus(query, outletId, limit));
    }

    @GetMapping("/supplier-skus/{skuId}")
    @Operation(
            summary = "One pack, in full",
            description = """
                    The page a kitchen decides on, as opposed to compares on: what the
                    pack is, what it measures, the gallery, a video if the supplier gave
                    one, and what other kitchens made of it.

                    Price, GST and availability are the live offer — the same figures the
                    shelf row shows, because a detail page that priced a SKU differently
                    from the row that led to it would be the worst possible place to
                    disagree.

                    `outletId` is optional and only supplies distance and an ETA.
                    `otherPacks` is the rest of this store's range for the same product,
                    which the comparison does not show: it ranks one pack per supplier.
                    """)
    public ApiResponse<DiscoveryDtos.SkuDetail> sku(
            @PathVariable Long skuId,
            @RequestParam(required = false) Long outletId) {
        if (outletId != null) {
            accessControl.requireScoped(ActorContext.requireUserId(),
                    Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        }
        return ApiResponse.ok(skuDetails.detail(skuId, outletId));
    }

    @GetMapping("/supplier-stores/{storeId}/storefront")
    @Operation(
            summary = "The head of one supplier's shelf",
            description = """
                    Who this branch is, how far and how long, what other kitchens rated
                    them, the supplier's other branches, and what credit this supplier has
                    extended **this outlet**.

                    `outletId` is optional, and is what makes the response private: with
                    one, the caller must be scoped to that outlet, because credit terms
                    are the outlet's business. Without one there is no credit, no distance
                    and no ETA — only the branch and its rating.

                    `credit` is null when this supplier has extended this outlet nothing,
                    which covers both "never asked" and "was turned down"; the app offers
                    to ask in either case. `available` is computed here and must never be
                    derived by the client (§23A.24) — it nets off reservations against
                    orders already in flight.

                    `etaMinutes` is preparation plus travel, the same estimate the product
                    comparison ranks on. Null rather than guessed when either end has no
                    coordinates.
                    """)
    public ApiResponse<DiscoveryDtos.StorefrontHeader> storefront(
            @PathVariable Long storeId,
            @RequestParam(required = false) Long outletId) {
        return ApiResponse.ok(storefronts.header(
                ActorContext.requireUserId(), storeId, outletId));
    }

    @GetMapping("/supplier-stores/{storeId}/catalog")
    @Operation(
            summary = "What one supplier store sells",
            description = """
                    The restaurant-facing view of a supplier's shelf, reached by tapping a
                    supplier in search.

                    Deliberately not filtered by serviceability: the restaurant asked for
                    this store, and an empty shelf would be a worse answer than the shelf
                    with its distance on it. `outletId` supplies that distance.
                    """)
    public ApiResponse<List<DiscoveryDtos.StorefrontSku>> storeCatalog(
            @PathVariable Long storeId,
            @RequestParam(required = false) Long outletId,
            @RequestParam(value = "q", required = false) String query) {
        if (outletId != null) {
            accessControl.requireScoped(ActorContext.requireUserId(),
                    Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        }
        return ApiResponse.ok(storefront.storeCatalog(storeId, outletId, query));
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

    @GetMapping("/outlets/{outletId}/suppliers/popular")
    @Operation(
            summary = "Suppliers worth putting in front of this kitchen",
            description = """
                    Each supplier comes with the **categories they actually stock**,
                    counted from purchasable offers rather than from anything they wrote
                    about themselves. "Dairy, Vegetables, Staples" is what decides whether
                    a store is worth opening; a name and a distance are not.

                    **"Popular" is a placeholder.** Ranking by orders placed, fill rate or
                    repeat business is a decision nobody has made yet, so this returns the
                    nearest active suppliers who list something — true, and the floor any
                    real ranking has to clear. Named for what it is meant to become so the
                    caller does not have to change when it does.

                    `categoryId` narrows it to suppliers stocking that aisle. Filtered in
                    SQL rather than over the returned `categories`, which is capped at six
                    for display — a browse page that quietly omits a supplier who stocks
                    the aisle is worse than no browse page.
                    """)
    public ApiResponse<List<DiscoveryDtos.PopularSupplier>> popularSuppliers(
            @PathVariable Long outletId,
            @Parameter(description = "How many to return. Defaults to 10.")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Only suppliers stocking this aisle.")
            @RequestParam(required = false) Long categoryId) {

        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        return ApiResponse.ok(
                popular.forOutlet(outletId, limit == null ? 10 : limit, categoryId));
    }
}
