package com.costonomy.mp.fooddelivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryQueryService;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations &amp; Admin endpoint for food delivery inspection.
 */
@RestController
@RequestMapping("/api/v1/admin/food-deliveries")
@RequiredArgsConstructor
@Tag(name = "Operations - Food Delivery")
public class AdminFoodDeliveryController {

    private final FoodDeliveryQueryService queryService;
    private final com.costonomy.mp.fooddelivery.service.FoodDeliveryResolutionService resolution;

    @GetMapping("/{id}/inspect")
    @Operation(summary = "Operator inspection view for a food delivery",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryInspectionResponse> inspect(@PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(queryService.inspect(actorId, id));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Close or clear a food delivery that cannot finish by itself",
               description = "CANCEL_AND_REFUND (cancel before pickup; after a failed hand-over, close as returned and refund "
                       + "the restaurant, making the courier's cost Costonomy's), MARK_DELIVERED, or CLEAR_FLAG. A reason is "
                       + "required. Requires DELIVERY_OPERATE permission at PLATFORM scope. Idempotent.")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryResponse> resolve(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody FoodDeliveryDtos.ResolveFoodDeliveryRequest request) {
        Long actorId = ActorContext.requireUserId();
        com.costonomy.mp.fooddelivery.service.FoodDeliveryResolutionService.Outcome outcome;
        try {
            outcome = com.costonomy.mp.fooddelivery.service.FoodDeliveryResolutionService.Outcome.valueOf(request.outcome());
        } catch (IllegalArgumentException ex) {
            throw new com.costonomy.mp.common.error.BusinessException(
                    com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                    "The outcome must be CANCEL_AND_REFUND, MARK_DELIVERED or CLEAR_FLAG.");
        }
        return ApiResponse.ok(resolution.resolve(actorId, id, outcome, request.reason()));
    }
}
