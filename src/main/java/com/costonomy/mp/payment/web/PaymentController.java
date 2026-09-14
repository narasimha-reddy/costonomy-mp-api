package com.costonomy.mp.payment.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.payment.domain.RefundReason;
import com.costonomy.mp.payment.repository.PaymentTransactionRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.payment.service.PaymentService;
import com.costonomy.mp.payment.service.RefundService;
import com.costonomy.mp.payment.web.dto.PaymentDtos;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Payments and refunds. Doc 04 §12. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final OrderReleaseService orderRelease;
    private final PaymentTransactionRepository transactions;
    private final RefundRepository refunds;
    private final AccessControlService accessControl;

    @GetMapping("/payments/{id}")
    @Operation(summary = "Get a payment and its movements")
    public ApiResponse<PaymentDtos.PaymentResponse> get(@PathVariable Long id) {
        var payment = paymentService.load(id);
        accessControl.requireScoped(ActorContext.requireUserId(), Permissions.ORDER_VIEW,
                ScopeType.OUTLET, payment.getOutletId(), "Payment");
        return ApiResponse.ok(toResponse(payment));
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

        var confirmed = paymentService.confirm(id, request.providerPaymentId());

        if (confirmed.getStatus().fundsSecured()) {
            orderRelease.releaseIfFunded(confirmed.getSupplierOrderId());
        } else if (confirmed.getStatus() == com.costonomy.mp.payment.domain.PaymentStatus.FAILED) {
            orderRelease.abandonUnfunded(confirmed.getSupplierOrderId(),
                    "Payment failed: " + String.valueOf(confirmed.getFailureCode()));
        }

        return ApiResponse.ok(toResponse(paymentService.load(id)));
    }

    @PostMapping("/payments/{id}/refund")
    @Operation(
            summary = "Request a refund",
            description = """
                    Returns money that was captured. Money merely held is released instead,
                    automatically, when a supplier rejects or times out — that is not a
                    refund and does not appear as one.

                    Requires an `Idempotency-Key`: a duplicate refund is money leaving
                    twice, and a repeated request returns the original rather than issuing
                    another.

                    Reasons: SUPPLIER_REJECTION, PARTIAL_ACCEPTANCE, CANCELLATION,
                    DELIVERY_FAILURE, DISPUTE_RESOLVED, DUPLICATE_PAYMENT,
                    PROVIDER_REVERSAL.
                    """)
    public ApiResponse<PaymentDtos.RefundResponse> refund(
            @PathVariable Long id,
            @Valid @RequestBody PaymentDtos.RequestRefundRequest request,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {

        var payment = paymentService.load(id);
        accessControl.requireScoped(ActorContext.requireUserId(), Permissions.PAYMENT_CREATE,
                ScopeType.OUTLET, payment.getOutletId(), "Payment");

        if (!RefundReason.isValid(request.reason())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose one of the listed refund reasons.");
        }

        var refund = refundService.request(ActorContext.requireUserId(), id, request.amount(),
                RefundReason.valueOf(request.reason()), request.note(), idempotencyKey);

        return ApiResponse.ok(toResponse(refund));
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
                refund.getReason().name(), refund.getStatus(), refund.getFailureReason(),
                refund.getCompletedAt(), refund.getCreatedAt());
    }
}
