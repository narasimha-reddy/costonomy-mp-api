package com.costonomy.mp.fooddelivery.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryQueryService;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
    private final Directory directory;

    @PostMapping
    @Operation(summary = "Request a food delivery for an outlet")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryResponse> create(
            @PathVariable Long outletId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FoodDeliveryDtos.CreateFoodDeliveryRequest body) {

        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.OUTLET_EDIT, ScopeType.OUTLET, outletId);

        // The restaurant of an outlet is its business in common (D-208).
        Long restaurantId = directory.outlet(outletId)
                .filter(o -> "OUTLET".equals(o.kind()))
                .map(AppOutlet::orgId).orElse(null);
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
                body.maxFee(),
                body.paymentMethod()
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

    @GetMapping("/{id}/financials")
    @Operation(summary = "What this delivery cost the restaurant and where any refund stands",
            description = "Charged, kept, refunded (to the wallet or to the original payment) and in progress. "
                    + "Never shows the courier's cost.")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryFinancialsResponse> financials(
            @PathVariable Long outletId, @PathVariable Long id) {
        return ApiResponse.ok(queryService.financials(ActorContext.requireUserId(), outletId, id));
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

    @PostMapping("/{id}/pay")
    @Operation(summary = "Initiate payment gateway checkout for food delivery fee",
            description = "Opens a Razorpay checkout authorization intent with auto-capture for the delivery fee.")
    public ApiResponse<FoodDeliveryDtos.InitiateFoodDeliveryPaymentResponse> initiatePayment(
            @PathVariable Long outletId,
            @PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.OUTLET_EDIT, ScopeType.OUTLET, outletId);
        return ApiResponse.ok(bookingService.initiatePayment(outletId, id));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm payment gateway checkout for food delivery fee",
            description = "Verifies checkout signature and payment capture with Razorpay, marks fee paid, and triggers courier booking.")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryResponse> confirmPayment(
            @PathVariable Long outletId,
            @PathVariable Long id,
            @Valid @RequestBody FoodDeliveryDtos.ConfirmFoodDeliveryPaymentRequest request) {
        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.OUTLET_EDIT, ScopeType.OUTLET, outletId);
        var delivery = bookingService.confirmPayment(outletId, id, request);
        return ApiResponse.ok(queryService.toResponse(delivery));
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
