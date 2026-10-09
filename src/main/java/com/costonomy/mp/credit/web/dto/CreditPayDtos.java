package com.costonomy.mp.credit.web.dto;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditDueState;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPayInvoiceOutcome;
import com.costonomy.mp.credit.domain.CreditPayMethod;
import com.costonomy.mp.credit.domain.CreditPaySkipReason;
import com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Paying whole credit invoices online (UPI, card) or from the wallet, no supplier approval (D-196). The app never
 * sends an amount to pay: it sends invoice ids and the {@code outstanding} it was shown, as a guard. The server
 * computes every total, fee and shortfall.
 */
public final class CreditPayDtos {

    private CreditPayDtos() {
    }

    // ── Requests ─────────────────────────────────────────────────────────

    /** One invoice to pay, with the outstanding amount the app was shown (copied verbatim from the server). */
    public record PayItem(
            @NotNull(message = "Choose an invoice") Long invoiceId,
            @NotNull(message = "Send the outstanding amount you were shown")
            @DecimalMin(value = "0.00", inclusive = false, message = "The outstanding amount must be more than zero")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal outstanding) {
    }

    public record QuoteRequest(
            @NotNull(message = "Choose at least one invoice.")
            @Size(min = 1, message = "Choose at least one invoice.")
            @Size(max = 50, message = "Choose at most 50 invoices at a time.")
            List<@Valid @NotNull(message = "Choose an invoice") PayItem> items) {
    }

    public record CreateRequest(
            @NotNull(message = "Choose at least one invoice.")
            @Size(min = 1, message = "Choose at least one invoice.")
            @Size(max = 50, message = "Choose at most 50 invoices at a time.")
            List<@Valid @NotNull(message = "Choose an invoice") PayItem> items,
            @NotNull(message = "Choose how to pay") CreditPayMethod method) {
    }

    public record VerifyRequest(
            // Letters, digits and underscores (pay_..., mock_pay_...): nothing else reaches the provider's URL or our logs.
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_]+", message = "That isn't a payment id")
            String razorpayPaymentId,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_\\-]+", message = "That isn't a signature")
            String razorpaySignature) {
    }

    // ── The picker ───────────────────────────────────────────────────────

    public record PayableInvoice(
            Long invoiceId,
            String invoiceNumber,
            Long supplierOrderId,
            CreditInvoiceStatus status,
            BigDecimal amount,
            BigDecimal paidAmount,
            BigDecimal creditedAmount,
            BigDecimal outstanding,
            LocalDate dueDate,
            LocalDate overdueAfter,
            CreditDueState dueState,
            Integer daysToDue,
            BigDecimal openClaimsAmount) {
    }

    public record PayableSupplier(
            Long agreementId,
            Long supplierStoreId,
            String supplierName,
            String storeName,
            CreditAgreementStatus agreementStatus,
            boolean payable,
            String notPayableReason,
            BigDecimal due,
            BigDecimal overdue,
            List<PayableInvoice> invoices) {
    }

    public record PayableResponse(
            Long outletId,
            boolean onlinePayEnabled,
            boolean walletPayEnabled,
            List<CreditPayMethod> methods,
            BigDecimal feePercent,
            BigDecimal minOnlineAmount,
            int maxInvoices,
            List<PayableSupplier> suppliers) {
    }

    // ── The quote ────────────────────────────────────────────────────────

    /** {@code unavailableReason}: ONLINE_OFF | BELOW_MINIMUM | WALLET_OFF | WALLET_ON_HOLD | WALLET_SHORT. */
    public record MethodQuote(
            CreditPayMethod method,
            boolean available,
            String unavailableReason,
            BigDecimal feeAmount,
            BigDecimal totalCharged,
            BigDecimal shortBy) {
    }

    public record QuoteResponse(
            int invoiceCount,
            int supplierCount,
            BigDecimal amount,
            BigDecimal walletBalance,
            List<MethodQuote> methods) {
    }

    // ── An attempt ───────────────────────────────────────────────────────

    public record AttemptInvoice(
            Long invoiceId,
            String invoiceNumber,
            Long agreementId,
            Long supplierStoreId,
            String supplierName,
            BigDecimal amount,
            CreditPayInvoiceOutcome outcome,
            CreditPaySkipReason skipReason) {
    }

    public record AttemptSupplier(
            Long agreementId,
            Long supplierStoreId,
            String supplierName,
            int invoiceCount,
            BigDecimal amount,
            BigDecimal paidAmount,
            Long repaymentId,
            CreditDtos.RepaymentAgreementState agreement) {
    }

    /**
     * The result of every credit payment call. {@code paidWith}: UPI | CARD | NETBANKING | PROVIDER_WALLET | EMI |
     * OTHER | WALLET, or null until paid. {@code refundState}: NONE | PENDING | COMPLETED | NEEDS_ATTENTION.
     * {@code keyId} only on create and reopen of a CREATED attempt.
     */
    public record AttemptResponse(
            Long attemptId,
            Long outletId,
            CreditPaymentAttemptStatus status,
            CreditPayMethod method,
            String paidWith,
            String paymentDetail,
            int invoiceCount,
            BigDecimal amount,
            BigDecimal feeAmount,
            BigDecimal totalCharged,
            String currency,
            String razorpayOrderId,
            String razorpayPaymentId,
            String keyId,
            boolean reopened,
            BigDecimal refundAmount,
            String refundState,
            Instant createdAt,
            Instant paidAt,
            Instant expiresAt,
            BigDecimal walletBalanceAfter,
            List<AttemptSupplier> suppliers,
            List<AttemptInvoice> invoices) {
    }
}
