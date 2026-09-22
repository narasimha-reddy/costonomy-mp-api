package com.costonomy.mp.delivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.delivery.service.AdminDeliveryService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/deliveries")
@RequiredArgsConstructor
@Tag(name = "Operations - Delivery")
public class AdminDeliveryController {

    private final AdminDeliveryService adminDeliveryService;

    @GetMapping("/{id}/ledger")
    @Operation(summary = "Audit trail for delivery financial ledger",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<List<DeliveryDtos.DeliveryLedgerResponse>> ledger(@PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getLedger(actorId, id));
    }

    @PostMapping("/{id}/force-waterfall")
    @Operation(summary = "Manually trigger carrier waterfall escalation",
               description = "Requires DELIVERY_OPERATE permission at PLATFORM scope.")
    public ApiResponse<DeliveryDtos.DeliveryResponse> forceWaterfall(
            @PathVariable Long id,
            @RequestBody(required = false) DeliveryDtos.ReassignDeliveryRequest request) {
        Long actorId = ActorContext.requireUserId();
        String reason = request != null && request.reason() != null
                ? request.reason()
                : "Operations manual escalation";
        return ApiResponse.ok(adminDeliveryService.forceWaterfall(actorId, id, reason));
    }
}
