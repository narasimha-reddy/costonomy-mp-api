package com.costonomy.mp.delivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.delivery.service.DeliveryPolicyService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/supplier-stores/{storeId}/delivery-policy")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Delivery")
public class DeliveryPolicyController {

    private final DeliveryPolicyService policies;

    @GetMapping
    @Operation(summary = "This store's delivery terms",
            description = "Absent means the platform default: Costonomy delivery only.")
    public ApiResponse<DeliveryDtos.DeliveryPolicyResponse> get(@PathVariable Long storeId) {
        return ApiResponse.ok(policies.get(ActorContext.requireUserId(), storeId));
    }

    @PutMapping
    @Operation(summary = "Set this store's delivery terms",
            description = """
                    At least one of own delivery and Costonomy delivery must stay on.
                    Both off is not a policy, it is a store nobody can buy from — and it
                    would surface at checkout as "no delivery partner" rather than as
                    the setting that caused it.
                    """)
    public ApiResponse<DeliveryDtos.DeliveryPolicyResponse> put(
            @PathVariable Long storeId,
            @Valid @RequestBody DeliveryDtos.UpdateDeliveryPolicyRequest request) {
        return ApiResponse.ok(policies.put(ActorContext.requireUserId(), storeId, request));
    }
}
