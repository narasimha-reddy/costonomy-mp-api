package com.costonomy.mp.fooddelivery.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryQueryService;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/outlets/{outletId}/food-deliveries")
@RequiredArgsConstructor
@Tag(name = "Food Delivery")
public class FoodDeliveryController {

    private final FoodDeliveryBookingService bookingService;
    private final FoodDeliveryQueryService queryService;
    private final AccessControlService accessControl;
    private final JdbcTemplate jdbc;

    @PostMapping
    @Operation(summary = "Request a food delivery for an outlet")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryResponse> create(
            @PathVariable Long outletId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FoodDeliveryDtos.CreateFoodDeliveryRequest body) {

        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.OUTLET_EDIT, ScopeType.OUTLET, outletId);

        Long restaurantId = jdbc.query("select restaurant_id from outlet where id = ?",
                (rs, row) -> rs.getLong(1), outletId).stream().findFirst().orElse(null);
        if (restaurantId == null) {
            throw new NotFoundException("Outlet", outletId);
        }

        var req = new FoodDeliveryBookingService.CreateRequest(
                restaurantId,
                outletId,
                idempotencyKey,
                body.externalOrderRef(),
                body.customerName(),
                body.customerPhone(),
                body.dropAddress(),
                body.dropLandmark(),
                body.dropLatitude(),
                body.dropLongitude(),
                body.dropCity(),
                body.dropState(),
                body.dropPincode(),
                body.dropInstructions(),
                body.orderValue(),
                body.weightKg(),
                body.maxDeliveryMinutes(),
                body.maxFee()
        );

        var delivery = bookingService.create(req);
        return ApiResponse.ok(queryService.toResponse(delivery));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a food delivery by id")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryResponse> get(
            @PathVariable Long outletId,
            @PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(queryService.get(actorId, outletId, id));
    }

    @GetMapping
    @Operation(summary = "List food deliveries for an outlet")
    public ApiResponse<List<FoodDeliveryDtos.FoodDeliverySummaryResponse>> list(
            @PathVariable Long outletId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(queryService.list(actorId, outletId, status, page, size));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a food delivery before pickup")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryResponse> cancel(
            @PathVariable Long outletId,
            @PathVariable Long id,
            @RequestBody(required = false) FoodDeliveryDtos.CancelFoodDeliveryRequest body) {
        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.OUTLET_EDIT, ScopeType.OUTLET, outletId);

        // Verify outlet ownership
        queryService.get(actorId, outletId, id);

        String reason = body != null && body.reason() != null ? body.reason() : "Cancelled by outlet";
        var cancelled = bookingService.cancel(id, reason);
        return ApiResponse.ok(queryService.toResponse(cancelled));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Get the timeline for a food delivery")
    public ApiResponse<List<FoodDeliveryDtos.TimelineResponse>> timeline(
            @PathVariable Long outletId,
            @PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(queryService.timeline(actorId, outletId, id));
    }
}
