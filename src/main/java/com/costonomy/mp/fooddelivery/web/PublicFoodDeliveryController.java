package com.costonomy.mp.fooddelivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryQueryService;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Public tracking link for food deliveries (D-197, doc 06 §16).
 * Unauthenticated; accessed via unguessable 32-character tracking token.
 * Never exposes customer phone number, internal provider codes, quotes, fees, or OTP.
 */
@RestController
@RequestMapping("/api/v1/public/food-deliveries")
@RequiredArgsConstructor
@Tag(name = "Public Food Delivery Tracking")
public class PublicFoodDeliveryController {

    private final FoodDeliveryQueryService queryService;

    @GetMapping("/{trackingToken}")
    @Operation(summary = "Track a food delivery using its public tracking token")
    public ApiResponse<FoodDeliveryDtos.PublicTrackingResponse> track(
            @PathVariable String trackingToken) {
        return ApiResponse.ok(queryService.trackByToken(trackingToken));
    }
}
