package com.costonomy.mp.payment.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.payment.service.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Razorpay webhooks. Doc 04 §12, doc 09 §5.
 *
 * <p>Unauthenticated by bearer token — a provider has no user session — and
 * authenticated instead by signature. That is why {@code SecurityConfig} permits
 * this path and why the handler verifies before doing anything at all.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@SecurityRequirements
@Tag(name = "Webhooks")
public class PaymentWebhookController {

    private final PaymentWebhookService webhooks;

    @PostMapping("/razorpay")
    @Operation(
            summary = "Razorpay payment webhook",
            description = """
                    Verified by signature, deduplicated on the provider's event id, and
                    stored before it is acted on.

                    Always returns 200 for an event we accepted, including a duplicate —
                    the provider's question is "did you receive this", and a non-200 makes
                    them retry something already handled. An invalid signature is the one
                    case that fails.
                    """)
    public ApiResponse<Map<String, Object>> razorpay(
            // The raw body, not a parsed object: the signature covers exact bytes,
            // and a parse-and-reserialise round trip changes them.
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        boolean processed = webhooks.handle(rawBody, signature);
        return ApiResponse.ok(Map.of("received", true, "processed", processed));
    }
}
