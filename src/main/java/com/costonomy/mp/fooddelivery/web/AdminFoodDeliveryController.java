package com.costonomy.mp.fooddelivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryQueryService;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/{id}/inspect")
    @Operation(summary = "Operator inspection view for a food delivery",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryInspectionResponse> inspect(@PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(queryService.inspect(actorId, id));
    }
}
