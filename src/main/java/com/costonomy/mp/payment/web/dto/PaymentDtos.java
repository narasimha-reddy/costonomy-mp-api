package com.costonomy.mp.payment.web.dto;

import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.RefundStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record ConfirmPaymentRequest(
            /**
             * The provider's payment id from the client's checkout.
             *
             * <p>All the client is trusted with: it says *which* payment to look at.
             * What state that payment is in comes from the provider, never from
             * here (doc 01 §14, guardrail 3).
             */
            @NotBlank(message = "providerPaymentId is required")
            String providerPaymentId) {
    }

    /**
     * @param fundsSecured whether the supplier order may now be released. The
     *                     client should not infer this from `status` — the rule is
     *                     the server's.
     */
    public record PaymentResponse(
            Long id,
            Long supplierOrderId,
            String orderNumber,
            PaymentStatus status,
            String provider,
            String providerOrderId,
            BigDecimal authorizedAmount,
            BigDecimal capturedAmount,
            BigDecimal refundedAmount,
            BigDecimal releasedAmount,
            String currency,
            String failureCode,
            String failureReason,
            boolean fundsSecured,
            Instant authorizedAt,
            Instant capturedAt,
            List<TransactionResponse> transactions) {
    }

    public record TransactionResponse(
            String type,
            BigDecimal amount,
            String status,
            String providerReference,
            Instant createdAt) {
    }

    public record RequestRefundRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "The refund must be more than zero")
            BigDecimal amount,
            @NotBlank(message = "Choose a reason")
            String reason,
            @Size(max = 500) String note) {
    }

    public record RefundResponse(
            Long id,
            Long paymentId,
            BigDecimal amount,
            String reason,
            RefundStatus status,
            String failureReason,
            Instant completedAt,
            Instant createdAt) {
    }
}
