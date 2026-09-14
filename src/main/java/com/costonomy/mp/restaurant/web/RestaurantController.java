package com.costonomy.mp.restaurant.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.restaurant.service.RestaurantService;
import com.costonomy.mp.restaurant.web.dto.RestaurantDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Restaurants and their outlets. Doc 04 §5. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Restaurants")
public class RestaurantController {

    private final RestaurantService restaurantService;

    @PostMapping("/restaurants")
    @Operation(
            summary = "Create a restaurant",
            description = """
                    The caller becomes its Owner. Optionally creates the first outlet in
                    the same call, so onboarding (§23A.8) is one round trip.
                    """)
    public ApiResponse<RestaurantDtos.RestaurantResponse> create(
            @Valid @RequestBody RestaurantDtos.CreateRestaurantRequest request) {
        return ApiResponse.ok(restaurantService.create(ActorContext.requireUserId(), request));
    }

    @GetMapping("/restaurants/{id}")
    @Operation(
            summary = "Get a restaurant and its outlets",
            description = "Returns 404 for a restaurant the caller cannot access, "
                    + "so ids cannot be probed.")
    public ApiResponse<RestaurantDtos.RestaurantResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(restaurantService.get(ActorContext.requireUserId(), id));
    }

    @PatchMapping("/restaurants/{id}")
    @Operation(summary = "Update a restaurant")
    public ApiResponse<RestaurantDtos.RestaurantResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantDtos.UpdateRestaurantRequest request) {
        return ApiResponse.ok(restaurantService.update(ActorContext.requireUserId(), id, request));
    }

    @GetMapping("/restaurants/{id}/outlets")
    @Operation(summary = "List a restaurant's outlets")
    public ApiResponse<List<RestaurantDtos.OutletResponse>> outlets(@PathVariable Long id) {
        return ApiResponse.ok(restaurantService.outletsOf(ActorContext.requireUserId(), id));
    }

    @PostMapping("/restaurants/{id}/outlets")
    @Operation(summary = "Add an outlet")
    public ApiResponse<RestaurantDtos.OutletResponse> createOutlet(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantDtos.CreateOutletRequest request) {
        return ApiResponse.ok(
                restaurantService.createOutlet(ActorContext.requireUserId(), id, request));
    }

    @GetMapping("/outlets/{id}")
    @Operation(summary = "Get an outlet")
    public ApiResponse<RestaurantDtos.OutletResponse> getOutlet(@PathVariable Long id) {
        return ApiResponse.ok(restaurantService.getOutlet(ActorContext.requireUserId(), id));
    }

    @PatchMapping("/outlets/{id}")
    @Operation(summary = "Update an outlet")
    public ApiResponse<RestaurantDtos.OutletResponse> updateOutlet(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantDtos.UpdateOutletRequest request) {
        return ApiResponse.ok(
                restaurantService.updateOutlet(ActorContext.requireUserId(), id, request));
    }

    @GetMapping("/outlets/{id}/users")
    @Operation(
            summary = "Who can act on this outlet",
            description = "Each entry carries the permissions that member actually holds "
                    + "here, including those inherited from a restaurant-level role.")
    public ApiResponse<List<RestaurantDtos.OutletUserResponse>> outletUsers(@PathVariable Long id) {
        return ApiResponse.ok(restaurantService.outletUsers(ActorContext.requireUserId(), id));
    }

    @PostMapping("/outlets/{id}/users")
    @Operation(
            summary = "Add a member to an outlet",
            description = """
                    Invites by phone number — the person need not have an account yet.
                    Only restaurant-side roles (`REST_*`) can be granted here.
                    """)
    public ApiResponse<RestaurantDtos.OutletUserResponse> addOutletUser(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantDtos.AddOutletUserRequest request) {
        return ApiResponse.ok(
                restaurantService.addOutletUser(ActorContext.requireUserId(), id, request));
    }
}
