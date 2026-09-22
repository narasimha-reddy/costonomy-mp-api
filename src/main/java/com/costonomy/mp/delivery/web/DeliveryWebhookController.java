package com.costonomy.mp.delivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.delivery.provider.pidge.PidgeWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Pidge Delivery Webhook Controller.
 *
 * <p>Receives courier status events (ALLOCATED, REACHED_PICKUP, OUT_FOR_DELIVERY,
 * DELIVERED) directly from Pidge Smart Dispatch.
 */
@RestController
@RequestMapping("/api/v1/webhooks/delivery")
@RequiredArgsConstructor
@SecurityRequirements
@Tag(name = "Delivery Webhooks")
public class DeliveryWebhookController {

    private final PidgeWebhookService pidgeWebhooks;

    @PostMapping("/pidge")
    @Operation(
            summary = "Pidge delivery webhook",
            description = """
                    Receives real-time delivery milestone events from Pidge.
                    Authenticated via HMAC-SHA256 signature, deduplicated on provider event id,
                    and protected against out-of-order delivery state transitions.
                    """)
    public ApiResponse<Map<String, Object>> pidge(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Pidge-Signature", required = false) String signature) {

        boolean processed = pidgeWebhooks.handle(rawBody, signature);
        return ApiResponse.ok(Map.of("received", true, "processed", processed));
    }
}
