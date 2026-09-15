package com.costonomy.mp.payment.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.payment.service.PaymentSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulates the customer completing checkout. Mock providers only.
 *
 * <p>The gates live in {@link PaymentSimulationService}, next to the work they
 * guard, as they do for delivery simulation.
 */
@RestController
@RequestMapping("/api/v1/internal/payments/{id}/simulate-checkout")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payments")
public class PaymentSimulationController {

    private final PaymentSimulationService simulation;

    @PostMapping
    @Operation(
            summary = "Complete a mock checkout (internal, mock providers only)",
            description = """
                    Stands in for the provider's hosted checkout, which a mock does not
                    have. Returns the provider payment id; pass it to
                    `POST /payments/{id}/confirm` exactly as a real client would, so the
                    confirmation path itself is still the real one.

                    Requires PAYMENT_CREATE on the payment's outlet — the same permission
                    that created it — and refuses outright unless the configured provider
                    is a mock.
                    """)
    public ApiResponse<CheckoutResult> simulate(@PathVariable Long id) {
        String providerPaymentId = simulation.completeCheckout(ActorContext.requireUserId(), id);
        return ApiResponse.ok(new CheckoutResult(providerPaymentId));
    }

    public record CheckoutResult(String providerPaymentId) {
    }
}
