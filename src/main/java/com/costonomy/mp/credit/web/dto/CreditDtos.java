package com.costonomy.mp.credit.web.dto;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditDueState;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditClaimStatus;
import com.costonomy.mp.credit.domain.CreditPaymentMethod;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
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

    /**
     * What the restaurant accepts. {@code termsVersion} is optional: the version of the terms it was shown. When
     * present and no longer current the accept is refused with {@code CREDIT_TERMS_CHANGED}.
     */
    public record AcceptRequest(Integer termsVersion) {
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
            /**
             * Who and where, in the shape an incoming order already uses.
             * <p>A supplier deciding on credit is deciding about a restaurant, and
             * the outlet's own name — whatever they chose to call it — is not
             * enough to know who is asking or how far away they are.
             */
            String restaurantName,
            String outletLocality,
            String outletCity,
            /** Kilometres from this store to the outlet, or null when unlocated. */
            BigDecimal distanceKm,
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
            RequestResponse latestRequest,
            /** The earliest due date among open invoices, or null when nothing is owed. */
            LocalDate nextDueDate,
            /** The outstanding total of the open invoices due on {@code nextDueDate}, or null. */
            BigDecimal nextDueAmount,
            /** How many invoices are still open. */
            int openInvoices,
            /** What the restaurant says it has paid and the supplier has not answered yet (D-125); 0 when none. */
            BigDecimal openClaimsAmount,
            /** What can still be reported as paid across the open invoices (D-127); never below 0. */
            BigDecimal reportableAmount,
            /** When the offer awaiting the restaurant was made; null unless APPROVED (D-137). */
            Instant offerMadeAt,
            /** The India day the offer lapses if still unaccepted; null unless APPROVED (D-137). */
            LocalDate offerExpiresOn) {
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
            List<AgreementResponse> agreements,
            /** Whether repaying from the wallet is switched on; the app hides 'Pay from wallet' when false. */
            boolean walletRepayEnabled,
            /** The sum of the agreements' open "I paid" claims (D-125); 0 when none. */
            BigDecimal openClaimsAmount,
            /** The sum of the agreements' reportable amounts (D-127). */
            BigDecimal reportableAmount) {
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
            Instant settledAt,
            /** What to show about the due date; computed here in India time, never by the app. */
            CreditDueState dueState,
            /** Days until the due date (negative once past it); null for a settled invoice. */
            Integer daysToDue,
            /** Outstanding less the "I paid" claims awaiting the supplier, never below 0; 0 once settled (D-127). */
            BigDecimal reportableAmount) {
    }

    /** One payment against an invoice. */
    public record InvoicePaymentResponse(
            Long id,
            BigDecimal amount,
            CreditPaymentSource source,
            String method,
            String reference,
            Instant paidAt,
            /** The wallet ledger entry that funded it, for a WALLET payment; null otherwise. */
            Long walletEntryId) {
    }

    /** An invoice with who it is from, which order it is for, and what has been paid against it. */
    public record InvoiceDetailResponse(
            Long id,
            String invoiceNumber,
            Long agreementId,
            Long supplierOrderId,
            CreditInvoiceStatus status,
            BigDecimal amount,
            BigDecimal paidAmount,
            BigDecimal outstanding,
            LocalDate dueDate,
            LocalDate overdueAfter,
            Instant issuedAt,
            Instant settledAt,
            CreditDueState dueState,
            Integer daysToDue,
            String orderNumber,
            String supplierName,
            String storeName,
            List<InvoicePaymentResponse> payments,
            /** Every "I paid" claim on this invoice, newest first (D-125). */
            List<ClaimResponse> claims,
            /** Outstanding less the "I paid" claims awaiting the supplier, never below 0; 0 once settled (D-127). */
            BigDecimal reportableAmount,
            /** Every time the supplier moved the due date, newest first; empty when never (D-138). */
            List<CreditLifecycleDtos.DueExtensionResponse> extensions) {
    }

    /** What the Home Credit tile needs: whether to show the attention dot. No amounts. */
    public record AttentionResponse(boolean overdue, boolean dueSoon) {
    }

    /**
     * One line of a statement. {@code amount} is the signed change to what is owed (+ an order, - a repayment),
     * and {@code owedAfter} what was owed once it was applied.
     */
    public record StatementLine(
            Instant at,
            CreditTransactionType type,
            String label,
            BigDecimal amount,
            BigDecimal owedAfter,
            Long supplierOrderId,
            String orderNumber,
            Long creditInvoiceId,
            String invoiceNumber,
            CreditPaymentSource source,
            String method,
            String reference,
            Long walletEntryId) {
    }

    public record StatementResponse(
            Long agreementId,
            LocalDate from,
            LocalDate to,
            BigDecimal openingOwed,
            BigDecimal closingOwed,
            List<StatementLine> lines) {
    }

    // ── Repayment ────────────────────────────────────────────────────────

    public record RecordPaymentRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "The payment must be more than zero")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount,
            @NotBlank(message = "Choose how it was paid")
            @Pattern(regexp = CreditPaymentMethod.ALL_PATTERN,
                    message = "Choose BANK_TRANSFER, UPI, CASH, CHEQUE, CARD or ADJUSTMENT")
            String method,
            @Size(max = 200) String reference,
            @Size(max = 500) String note,
            /** When the money actually moved, which may not be now. */
            Instant paidAt) {
    }

    /**
     * Repay from the wallet (D-123). {@code invoiceIds} is optional: left out, the amount settles the agreement's open
     * invoices oldest due date first.
     */
    public record WalletRepaymentRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "Enter at least ₹1, or the exact remaining amount")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount,
            @Size(min = 1, message = "Choose at least one invoice, or leave the list out")
            List<@NotNull(message = "Choose an invoice") Long> invoiceIds) {

        @AssertTrue(message = "Each invoice can be chosen only once")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isInvoiceIdsDistinct() {
            return invoiceIds == null || invoiceIds.stream().distinct().count() == invoiceIds.size();
        }
    }

    public record WalletRepaymentAllocation(
            Long invoiceId, String invoiceNumber, BigDecimal amount, CreditInvoiceStatus statusAfter) {
    }

    /** The agreement as it stands after a repayment: what is still owed, what is late, what can be drawn. */
    public record RepaymentAgreementState(
            BigDecimal due, BigDecimal overdue, BigDecimal available, CreditAgreementStatus status) {
    }

    public record WalletRepaymentResponse(
            Long repaymentId,
            BigDecimal amount,
            Long walletEntryId,
            BigDecimal walletBalanceAfter,
            List<WalletRepaymentAllocation> allocations,
            RepaymentAgreementState agreement) {
    }

    // ── Supplier receipts (B5) ───────────────────────────────────────────

    /** What the supplier says it received for a whole credit line: one receipt, split over its open invoices. */
    public record SupplierPaymentRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "The payment must be more than zero")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount,
            @NotBlank(message = "Choose how it was paid")
            @Pattern(regexp = CreditPaymentMethod.CLAIMABLE_PATTERN,
                    message = "Choose BANK_TRANSFER, UPI, CASH, CHEQUE or CARD")
            String method,
            String reference,
            @NotNull(message = "Enter the date the money was received") LocalDate paidOn,
            @Size(max = 500) String note,
            @Size(min = 1, message = "Choose at least one invoice, or leave the list out")
            List<@NotNull(message = "Choose an invoice") Long> invoiceIds,
            Boolean allowDuplicateReference) {

        public static final int REFERENCE_MIN = 4;
        public static final int REFERENCE_MAX = 64;

        /** The reference without surrounding spaces, or null when there is none. */
        public String trimmedReference() {
            return reference == null || reference.isBlank() ? null : reference.trim();
        }

        /** UPI, bank transfer and cheque must say which payment it was; cash and card may not have a number. */
        @AssertTrue(message = "Enter the payment reference (4 to 64 characters)")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isReferenceValid() {
            String ref = trimmedReference();
            if (ref == null) {
                return "CASH".equals(method) || "CARD".equals(method) || method == null;
            }
            return ref.length() >= REFERENCE_MIN && ref.length() <= REFERENCE_MAX;
        }

        @AssertTrue(message = "Each invoice can be chosen only once")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isInvoiceIdsDistinct() {
            return invoiceIds == null || invoiceIds.stream().distinct().count() == invoiceIds.size();
        }

        public boolean duplicateAllowed() {
            return Boolean.TRUE.equals(allowDuplicateReference);
        }
    }

    /** What a receipt of this amount would do. A pure read: nothing is written. */
    public record SupplierPaymentPreviewRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "The payment must be more than zero")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount,
            @Size(min = 1, message = "Choose at least one invoice, or leave the list out")
            List<@NotNull(message = "Choose an invoice") Long> invoiceIds) {

        @AssertTrue(message = "Each invoice can be chosen only once")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isInvoiceIdsDistinct() {
            return invoiceIds == null || invoiceIds.stream().distinct().count() == invoiceIds.size();
        }
    }

    /** An open claim on an invoice the preview would pay (D-125): the supplier may want to confirm that instead. */
    public record PendingClaimWarning(Long invoiceId, String invoiceNumber, BigDecimal amount) {
    }

    public record SupplierPaymentPreviewResponse(
            BigDecimal amount,
            List<WalletRepaymentAllocation> allocations,
            RepaymentAgreementState agreement,
            List<PendingClaimWarning> pendingClaims) {
    }

    public record SupplierPaymentResponse(
            Long receiptId,
            BigDecimal amount,
            String method,
            String reference,
            LocalDate paidOn,
            List<WalletRepaymentAllocation> allocations,
            RepaymentAgreementState agreement) {
    }

    public record PaymentResponse(
            Long id,
            Long creditInvoiceId,
            BigDecimal amount,
            String method,
            String reference,
            Instant paidAt) {
    }

    // ── "I paid" claims (D-125) ──────────────────────────────────────────

    /**
     * A restaurant says it paid a supplier directly. {@code reference} is required unless the method is CASH.
     * Nothing changes until the supplier confirms.
     */
    public record ClaimRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "Enter at least ₹1, or the exact remaining amount")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount,
            @NotBlank(message = "Choose how you paid")
            @Pattern(regexp = CreditPaymentMethod.CLAIMABLE_PATTERN,
                    message = "Choose BANK_TRANSFER, UPI, CASH, CHEQUE or CARD")
            String method,
            @Size(max = 200) String reference,
            @NotNull(message = "Enter the date you paid") LocalDate paidOn,
            @Size(max = 500) String note) {

        @AssertTrue(message = "Enter the payment reference")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isReferenceGiven() {
            return "CASH".equals(method) || (reference != null && !reference.isBlank());
        }
    }

    /** The supplier confirms a claim. Leave {@code amount} out to confirm what was claimed, capped at what is owed. */
    public record ConfirmClaimRequest(
            @DecimalMin(value = "0.01", message = "The amount must be more than zero")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount) {
    }

    public record RejectClaimRequest(
            @NotBlank(message = "Give a reason") @Size(min = 3, max = 500, message = "Give a reason of 3 to 500 characters")
            String reason) {
    }

    /** One claim, as either side reads it. {@code creditPaymentId} is set once it is confirmed. */
    public record ClaimResponse(
            Long id,
            Long invoiceId,
            String invoiceNumber,
            Long agreementId,
            Long outletId,
            String outletName,
            String restaurantName,
            BigDecimal amount,
            CreditPaymentMethod method,
            String reference,
            LocalDate paidOn,
            String note,
            CreditClaimStatus status,
            String decisionNote,
            BigDecimal confirmedAmount,
            Long creditPaymentId,
            Instant createdAt,
            Instant decidedAt,
            /** India days since it was submitted, worked out here (D-139). */
            int ageDays,
            /** Still waiting for the supplier at 7 days or more. Never auto-rejected. */
            boolean stale,
            /** What is owed on the invoice now; 0 once settled. */
            BigDecimal invoiceOutstanding,
            /** All claims waiting for the supplier on the invoice, this one included while it waits. */
            BigDecimal invoiceOpenClaimsAmount,
            /** The same without this claim. */
            BigDecimal invoiceOtherOpenClaimsAmount,
            /** Another claim or a payment on this invoice with the same amount and the same reference or within 24h; else null. */
            Long possibleDuplicateOf,
            /** CLAIM or PAYMENT: which kind {@code possibleDuplicateOf} is; null when it is. */
            String possibleDuplicateKind) {
    }

    // ── The supplier's receivables (plan B3) ─────────────────────────────

    /** One page of a list: {@code total} rows in all, {@code hasNext} when another page follows. */
    public record PageOf<T>(List<T> items, int page, int size, long total, boolean hasNext) {
    }

    /** What the supplier store is owed, as of {@code asOf} (the India date). Every figure is worked out on the server. */
    public record ReceivablesResponse(
            LocalDate asOf,
            /** Outstanding on every open invoice of the store: overdue + inGrace + what is not yet due. */
            BigDecimal totalReceivable,
            /** Open invoices past their grace period (marked OVERDUE or not yet swept): {@code CreditDueState.OVERDUE}. */
            BigDecimal overdue,
            /** Past the due date but inside the grace period. */
            BigDecimal inGrace,
            /** Due on {@code asOf}. */
            BigDecimal dueToday,
            /** Not yet due and due from {@code asOf} through {@code asOf + 6} days, today included. */
            BigDecimal dueThisWeek,
            /** Payments received in the current India calendar month, whatever the source. */
            BigDecimal collectedThisMonth,
            Exposure exposure,
            Counts counts,
            /** Only what needs doing (count above zero), in display order; the app renders exactly this. */
            List<PendingAction> pendingActions) {
    }

    public record Exposure(
            /** Sum of the approved limits of ACTIVE lines. */
            BigDecimal extended,
            /** What those lines have drawn (utilized). */
            BigDecimal drawn,
            /** What those lines can still be ordered against: limit less drawn and reserved, never below zero per line. */
            BigDecimal availableToLend) {
    }

    public record Counts(
            /** Lines that are live (ACTIVE or SUSPENDED) or still owe something. */
            int restaurants,
            int linesActive,
            int linesSuspended,
            int requestsPending,
            int claimsWaiting,
            int overdueRestaurants) {
    }

    public enum PendingActionKind {
        CLAIMS_WAITING, REQUESTS_PENDING, OVERDUE_RESTAURANTS, LINE_AT_LIMIT
    }

    public record PendingAction(PendingActionKind kind, int count) {
    }

    /** One line (restaurant outlet) in the receivables list. */
    public record ReceivableRestaurantResponse(
            Long agreementId,
            Long outletId,
            String outletName,
            String restaurantName,
            CreditAgreementStatus status,
            BigDecimal owed,
            BigDecimal overdue,
            /** Outstanding on the invoices due on {@code nextDueDate}; null when nothing is owed. */
            BigDecimal nextDueAmount,
            /** The earliest due date among open invoices, or null. */
            LocalDate nextDueDate,
            /** The state of the worst open invoice (OVERDUE, IN_GRACE, DUE_TODAY, DUE_SOON, DUE_LATER); null when nothing is open. */
            CreditDueState dueState,
            int claimsWaiting,
            BigDecimal limit,
            /** What the line has drawn. */
            BigDecimal utilized,
            /** Drawn as a percentage of the limit, one decimal; null when the limit is zero. */
            BigDecimal utilization) {
    }

    public record AgeingBucketRestaurant(
            Long agreementId,
            String outletName,
            String restaurantName,
            BigDecimal amount,
            int invoiceCount) {
    }

    /** CURRENT, D1_7, D8_30 or D30_PLUS. */
    public record AgeingBucket(
            String bucket,
            BigDecimal amount,
            int invoiceCount,
            int restaurantCount,
            /** Up to five restaurants with the most in this bucket, biggest first. */
            List<AgeingBucketRestaurant> topRestaurants) {
    }

    public record AgeingResponse(LocalDate asOf, BigDecimal total, List<AgeingBucket> buckets) {
    }

    /** One payment in the supplier's feed. */
    public record PaymentFeedItem(
            Long id,
            Instant paidAt,
            /** The India calendar day of {@code paidAt}. */
            LocalDate paidOn,
            Long agreementId,
            Long outletId,
            String outletName,
            String restaurantName,
            Long invoiceId,
            String invoiceNumber,
            BigDecimal amount,
            CreditPaymentSource source,
            String method,
            String reference) {
    }
}
