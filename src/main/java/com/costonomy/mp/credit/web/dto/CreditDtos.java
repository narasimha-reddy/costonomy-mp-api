package com.costonomy.mp.credit.web.dto;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditRequestStatus;
import com.costonomy.mp.credit.domain.CreditReservationStatus;
import com.costonomy.mp.credit.domain.CreditTransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CreditDtos {

    private CreditDtos() {
    }

    // ── Requesting ───────────────────────────────────────────────────────

    public record CreateRequest(
            @NotNull(message = "Choose a supplier store") Long supplierStoreId,
            @NotNull(message = "Choose an outlet") Long outletId,
            @NotNull(message = "Enter a credit limit")
            @DecimalMin(value = "1.00", message = "The limit must be more than zero")
            BigDecimal requestedLimit,
            @NotNull(message = "Enter a credit period")
            @Min(value = 1, message = "The credit period must be at least a day")
            @Max(value = 180, message = "The credit period can't be longer than 180 days")
            Integer requestedDays,
            @Size(max = 64) String purpose,
            @Size(max = 1000) String note) {
    }

    /**
     * The supplier's answer.
     *
     * <p>Limit and period are optional: leaving them out approves exactly what was
     * asked for. Supplying either makes this a <em>modification</em>, which the
     * restaurant must accept before the credit becomes usable — doc 04 §13's
     * "supplier modification must be explicit and versioned".
     */
    public record ApproveRequest(
            @DecimalMin(value = "1.00", message = "The limit must be more than zero")
            BigDecimal approvedLimit,
            @Min(1) @Max(180) Integer creditPeriodDays,
            @Min(0) @Max(60) Integer gracePeriodDays,
            @DecimalMin(value = "1.00") BigDecimal maxSingleOrderCredit,
            @DecimalMin(value = "0.00") BigDecimal maxOverdueAmount,
            LocalDate effectiveFrom,
            LocalDate reviewDate,
            @Size(max = 1000) String note) {
    }

    public record RejectRequest(
            @NotBlank(message = "Give a reason") @Size(max = 1000) String reason) {
    }

    /** A supplier changing the terms of a live agreement. Doc 01 §18. */
    public record ModifyRequest(
            @NotNull @DecimalMin(value = "0.00") BigDecimal approvedLimit,
            @NotNull @Min(1) @Max(180) Integer creditPeriodDays,
            @Min(0) @Max(60) Integer gracePeriodDays,
            @DecimalMin(value = "1.00") BigDecimal maxSingleOrderCredit,
            @DecimalMin(value = "0.00") BigDecimal maxOverdueAmount,
            // Not optional. Doc 01 §18: every adjustment is auditable, and an
            // unexplained limit cut is the thing that requirement exists to stop.
            @NotBlank(message = "Give a reason for the change") @Size(max = 500) String reason) {
    }

    public record SuspendRequest(
            @NotBlank(message = "Give a reason") @Size(max = 500) String reason) {
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /**
     * @param available  always {@code approvedLimit - reserved - utilized}, computed
     *                   server-side. §23A.24: the app must never do this sum itself.
     * @param overdue    a subset of {@code due}, not a separate debt
     * @param canFund    whether an order may draw on this today — the app must not
     *                   infer it from {@code status}
     */
    public record AgreementResponse(
            Long id,
            Long outletId,
            String outletName,
            Long supplierStoreId,
            String storeName,
            String supplierName,
            CreditAgreementStatus status,
            BigDecimal approvedLimit,
            BigDecimal reserved,
            BigDecimal utilized,
            BigDecimal available,
            BigDecimal due,
            BigDecimal overdue,
            Integer creditPeriodDays,
            Integer gracePeriodDays,
            BigDecimal maxSingleOrderCredit,
            Integer termsVersion,
            LocalDate effectiveFrom,
            LocalDate reviewDate,
            String suspensionReason,
            boolean canFund,
            Instant activatedAt,
            RequestResponse latestRequest) {
    }

    public record RequestResponse(
            Long id,
            Long creditAgreementId,
            Long outletId,
            Long supplierStoreId,
            BigDecimal requestedLimit,
            Integer requestedPeriodDays,
            String purpose,
            String note,
            CreditRequestStatus status,
            String responseNote,
            Instant respondedAt,
            Instant createdAt) {
    }

    /** The outlet's whole credit position, across every supplier. §23A.24, doc 05 §19. */
    public record SummaryResponse(
            Long outletId,
            BigDecimal approvedLimit,
            BigDecimal reserved,
            BigDecimal utilized,
            BigDecimal available,
            BigDecimal due,
            BigDecimal overdue,
            List<AgreementResponse> agreements) {
    }

    public record LedgerEntryResponse(
            Long id,
            CreditTransactionType type,
            BigDecimal amount,
            BigDecimal reservedAfter,
            BigDecimal utilizedAfter,
            BigDecimal availableAfter,
            Long supplierOrderId,
            Long creditInvoiceId,
            String description,
            Instant createdAt) {
    }

    public record ReservationResponse(
            Long id,
            Long creditAgreementId,
            Long supplierOrderId,
            CreditReservationStatus status,
            BigDecimal reservedAmount,
            BigDecimal utilizedAmount,
            BigDecimal releasedAmount,
            String failureCode,
            String failureReason) {
    }

    public record InvoiceResponse(
            Long id,
            String invoiceNumber,
            Long creditAgreementId,
            Long supplierOrderId,
            CreditInvoiceStatus status,
            BigDecimal amount,
            BigDecimal paidAmount,
            BigDecimal outstanding,
            LocalDate dueDate,
            LocalDate overdueAfter,
            Instant issuedAt,
            Instant settledAt) {
    }

    // ── Repayment ────────────────────────────────────────────────────────

    public record RecordPaymentRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "The payment must be more than zero")
            BigDecimal amount,
            @NotBlank(message = "Choose how it was paid") String method,
            @Size(max = 200) String reference,
            @Size(max = 500) String note,
            /** When the money actually moved, which may not be now. */
            Instant paidAt) {
    }

    public record PaymentResponse(
            Long id,
            Long creditInvoiceId,
            BigDecimal amount,
            String method,
            String reference,
            Instant paidAt) {
    }
}
