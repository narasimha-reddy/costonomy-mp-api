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
            // A provider id is letters, digits and underscores (pay_…, mock_pay_…).
            // Anything else never reaches the provider's URL or our logs (D-101).
            @Size(max = 64, message = "That isn't a payment id")
            @Pattern(regexp = "[A-Za-z0-9_]+", message = "That isn't a payment id")
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

    /**
     * An order's payment, as the pay screen needs it (D-102).
     *
     * @param payable whether a checkout can still be opened against
     *                {@code providerOrderId}. False once funded, or once ended.
     */
    public record PaymentIntentResponse(
            Long paymentId,
            Long supplierOrderId,
            String provider,
            String providerOrderId,
            BigDecimal amount,
            String currency,
            String publicKey,
            PaymentStatus status,
            boolean fundsSecured,
            boolean payable,
            String failureReason,
            /** The order's status and funding method, so a screen reopened after a switch knows (D-152). */
            String orderStatus,
            String orderPaymentMethod,
            /** The unpaid card order can still be paid another way, or cancelled. */
            boolean switchable) {
    }

    public record TransactionResponse(
            String type,
            BigDecimal amount,
            String status,
            String providerReference,
            Instant createdAt) {
    }

    public record RefundResponse(
            Long id,
            Long paymentId,
            BigDecimal amount,
            String reason,
            /** WALLET (credited to the outlet's wallet) or ORIGINAL (to the card). D-104. */
            String destination,
            RefundStatus status,
            String failureReason,
            Instant completedAt,
            Instant createdAt) {
    }
}
