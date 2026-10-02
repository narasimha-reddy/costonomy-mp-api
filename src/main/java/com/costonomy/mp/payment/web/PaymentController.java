package com.costonomy.mp.payment.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.payment.repository.PaymentTransactionRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.payment.service.PaymentService;
import com.costonomy.mp.payment.web.dto.PaymentDtos;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Payments and refunds. Doc 04 §12. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final OrderReleaseService orderRelease;
    private final PaymentTransactionRepository transactions;
    private final RefundRepository refunds;
    private final AccessControlService accessControl;
    private final com.costonomy.mp.payment.provider.PaymentProvider provider;

    @GetMapping("/payments/{id}")
    @Operation(summary = "Get a payment and its movements")
    public ApiResponse<PaymentDtos.PaymentResponse> get(@PathVariable Long id) {
        var payment = paymentService.load(id);
        accessControl.requireScoped(ActorContext.requireUserId(), Permissions.ORDER_VIEW,
                ScopeType.OUTLET, payment.getOutletId(), "Payment");
        return ApiResponse.ok(toResponse(payment));
    }

    @GetMapping("/supplier-orders/{orderId}/payment-intent")
    @Operation(
            summary = "The order's payment, to pay or to check",
            description = """
                    What the pay screen needs, from the server rather than from what the
                    screen happened to keep: the provider order to open checkout against,
                    whether it can still be paid, and whether it already is. A read — it
                    never creates a provider order, so asking twice cannot charge twice.

                    Lets a restaurant pay an order after a refresh, after a long bank or
                    UPI flow, or from the order itself (D-102).
                    """)
    public ApiResponse<PaymentDtos.PaymentIntentResponse> intentForOrder(@PathVariable Long orderId) {
        var payment = paymentService.loadForOrder(orderId);
        accessControl.requireScoped(ActorContext.requireUserId(), Permissions.PAYMENT_CREATE,
                ScopeType.OUTLET, payment.getOutletId(), "Payment");

        boolean payable = payment.getStatus() == com.costonomy.mp.payment.domain.PaymentStatus.CREATED
                && payment.getProviderOrderId() != null;
        return ApiResponse.ok(new PaymentDtos.PaymentIntentResponse(
                payment.getId(), payment.getSupplierOrderId(), payment.getProvider(),
                payment.getProviderOrderId(), payment.getAuthorizedAmount(), payment.getCurrency(),
                payable ? publicKey(payment.getProvider()) : null,
                payment.getStatus(), payment.getStatus().fundsSecured(), payable,
                payment.getFailureReason()));
    }

    @PostMapping("/payments/{id}/confirm")
    @Operation(
            summary = "Confirm a payment after checkout",
            description = """
                    Tell us which payment to look at; we ask the provider what actually
                    happened. A client saying "it worked" is not financial truth, so this
                    endpoint takes only an id and verifies the rest.

                    On success the supplier order is released and its acceptance countdown
                    starts — not before, so a slow checkout cannot hand a supplier an
                    already-expired order.

                    You do not have to call this. A webhook does the same thing, and a
                    reconciliation job catches the case where neither arrives.
                    """)
    public ApiResponse<PaymentDtos.PaymentResponse> confirm(
            @PathVariable Long id,
            @Valid @RequestBody PaymentDtos.ConfirmPaymentRequest request) {

        var payment = paymentService.load(id);
        accessControl.requireScoped(ActorContext.requireUserId(), Permissions.PAYMENT_CREATE,
                ScopeType.OUTLET, payment.getOutletId(), "Payment");

        // After the access check, so an id a caller may not see is never logged
        // as theirs. The claimed provider id goes in as sent — it is the thing
        // being checked (D-100).
        try (var trace = TraceScope.of("payment", id, "order", payment.getSupplierOrderId(),
                "rzp_order", payment.getProviderOrderId(), "rzp_payment", request.providerPaymentId())) {
            log.info("Payment {} confirm requested", id);

            var confirmed = paymentService.confirm(id, request.providerPaymentId());

            if (confirmed.getStatus().fundsSecured()) {
                orderRelease.releaseIfFunded(confirmed.getSupplierOrderId());
            } else if (confirmed.getStatus() == com.costonomy.mp.payment.domain.PaymentStatus.FAILED) {
                orderRelease.abandonUnfunded(confirmed.getSupplierOrderId(),
                        "Payment failed: " + String.valueOf(confirmed.getFailureCode()));
            }

            return ApiResponse.ok(toResponse(paymentService.load(id)));
        }
    }

    @GetMapping("/payments/{id}/refunds")
    @Operation(summary = "Refunds against a payment")
    public ApiResponse<List<PaymentDtos.RefundResponse>> refundsFor(@PathVariable Long id) {
        var payment = paymentService.load(id);
        accessControl.requireScoped(ActorContext.requireUserId(), Permissions.ORDER_VIEW,
                ScopeType.OUTLET, payment.getOutletId(), "Payment");

        return ApiResponse.ok(refunds.findByPaymentIdOrderByCreatedAtDesc(id).stream()
                .map(PaymentController::toResponse).toList());
    }

    /** The publishable key, from the adapter — never a property someone could put a secret in. */
    private String publicKey(String providerName) {
        return provider.name().equals(providerName) ? provider.createAuthorizationPublicKey() : null;
    }

    private PaymentDtos.PaymentResponse toResponse(
            com.costonomy.mp.payment.domain.Payment payment) {

        return new PaymentDtos.PaymentResponse(
                payment.getId(), payment.getSupplierOrderId(), null,
                payment.getStatus(), payment.getProvider(), payment.getProviderOrderId(),
                payment.getAuthorizedAmount(), payment.getCapturedAmount(),
                payment.getRefundedAmount(), payment.getReleasedAmount(),
                payment.getCurrency(), payment.getFailureCode(), payment.getFailureReason(),
                payment.getStatus().fundsSecured(),
                payment.getAuthorizedAt(), payment.getCapturedAt(),
                transactions.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).stream()
                        .map(transaction -> new PaymentDtos.TransactionResponse(
                                transaction.getTransactionType(), transaction.getAmount(),
                                transaction.getStatus(), transaction.getProviderReference(),
                                transaction.getCreatedAt()))
                        .toList());
    }

    private static PaymentDtos.RefundResponse toResponse(
            com.costonomy.mp.payment.domain.Refund refund) {

        return new PaymentDtos.RefundResponse(
                refund.getId(), refund.getPaymentId(), refund.getAmount(),
                refund.getReason().name(), refund.getDestination().name(),
                refund.getStatus(), refund.getFailureReason(),
                refund.getCompletedAt(), refund.getCreatedAt());
    }
}
