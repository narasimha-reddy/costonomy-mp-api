package com.costonomy.mp.wallet.web.dto;

import com.costonomy.mp.wallet.domain.WalletDirection;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class WalletDtos {

    private WalletDtos() {
    }

    /** What an outlet has to spend, and why. */
    public record WalletResponse(
            Long outletId,
            BigDecimal balance,
            String currency,
            String status,
            List<EntryResponse> recent,
            /** What the wallet may hold and take in, and how much of the month is used (D-107). */
            Limits limits) {
    }

    /**
     * The wallet limits that stand in for KYC tiers (D-107). All rupees. "Month" is
     * the calendar month in Asia/Kolkata, and {@code addedThisMonth} counts only
     * top-ups paid through Razorpay and credited.
     */
    public record Limits(
            BigDecimal maxBalance,
            BigDecimal monthlyTopUpLimit,
            BigDecimal addedThisMonth,
            BigDecimal remainingThisMonth,
            BigDecimal minTopUp,
            BigDecimal maxTopUp) {
    }

    public record EntryResponse(
            Long id,
            WalletDirection direction,
            /**
             * Why it moved: TOP_UP, ORDER_PAYMENT, ORDER_REFUND, REFUND, WITHDRAWAL, DISPUTE_REFUND or
             * WITHDRAWAL_REVERSAL (a withdrawal part the provider did not send, put back).
             */
            String kind,
            BigDecimal amount,
            /** What the balance became, so a statement reads without arithmetic. */
            BigDecimal balanceAfter,
            Long supplierOrderId,
            String reason,
            /**
             * For a withdrawal, where its refund to the card has got to: REQUESTED,
             * PROCESSING, COMPLETED, FAILED (being retried), REJECTED (the provider refused it; being
             * checked before anything is decided), NEEDS_REVIEW (our team has it) or REVERSED (it was not
             * sent and the money is back in the wallet). Null for every other kind.
             */
            String refundStatus,
            Instant at,
            /** The bill status, exactly as the History list shows it for this entry (D-116): null or {@link Bill}. */
            Bill bill) {
    }

    /**
     * One line of the wallet history (D-108): a ledger row, or a top-up that was paid and
     * returned. The two live in different tables and their ids can coincide, so {@code key}
     * ("L12" or "T12") is what a list should use to tell rows apart.
     */
    public record HistoryItem(
            String key,
            Long id,
            WalletDirection direction,
            String kind,
            BigDecimal amount,
            /** What the balance became. Null for a returned top-up, which never touched it. */
            BigDecimal balanceAfter,
            Long supplierOrderId,
            String reason,
            /** COMPLETED, IN_PROGRESS (a withdrawal still on its way) or RETURNED. */
            String status,
            /** A withdrawal's refund status (as on the wallet), else null. */
            String refundStatus,
            /** "Card •1007", "UPI", "Netbanking"; null when unknown or not a top-up. */
            String instrument,
            Instant at,
            /**
             * The bill on a payment that needs one (D-116), or null: no bill applies, the payment is before the
             * tracking start, or it was marked 'No bill needed'.
             */
            Bill bill) {
    }

    /** PENDING, READING, ADDED, REVIEWED or UNREADABLE (D-116). */
    public record Bill(String status) {
    }

    /**
     * The History banner's counts (D-116): payments since the tracking start that need a bill (PENDING), and every
     * bill on the wallet being read, or not read and not yet filled in by hand. All months, whatever the filters; each
     * count equals what its {@code bills=} filter returns; 'No bill needed' is not counted, and a bill wins over it.
     */
    public record BillSummary(int pending, int reading, int unreadable) {
    }

    /**
     * One wallet entry in full, for the transaction-details page. Carries every field the list
     * item does ({@link HistoryItem}) plus who the other side was and what we hold to prove it.
     */
    public record TransactionDetail(
            String key,
            Long id,
            /** Our own transaction id: the ledger entry id as a plain decimal string, e.g. "184". */
            String transactionId,
            WalletDirection direction,
            String kind,
            BigDecimal amount,
            BigDecimal balanceAfter,
            Long supplierOrderId,
            String reason,
            String status,
            String refundStatus,
            String instrument,
            Instant at,
            /** Who was paid or who paid; null when we do not know. */
            String counterpartyName,
            /** A QuickScan payee's masked VPA, an order number, or null. Never a full VPA. */
            String counterpartyDetail,
            /** Real identifiers we hold, in display order. Our own transaction id is not repeated here. */
            List<Reference> references,
            Actions actions,
            /** The shop's bill on this payment, or null (D-113). */
            com.costonomy.mp.wallet.invoice.web.InvoiceDtos.Summary invoice,
            /**
             * D-116: PENDING, READING, ADDED, REVIEWED, UNREADABLE, NOT_REQUIRED ('No bill needed') or null, by the
             * History's rules (a payment before the tracking start without a bill is null).
             */
            String billStatus) {
    }

    public record Reference(String label, String value, boolean copyable) {
    }

    public record Actions(
            boolean canPayAgain,
            /** A bill can be attached: an order payment or a QuickScan payment (D-113). */
            boolean canAddBill,
            /** D-116: 'No bill needed' can be set: an eligible payment with no bill, not already marked. */
            boolean canWaiveBill,
            /** D-116: 'No bill needed' is set and can be undone. */
            boolean canUndoWaiver,
            /** The full VPA, present only when {@code canPayAgain}: the caller's own past payment. */
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String payeeVpa) {
    }

    /**
     * What a month's ledger added and spent, in rupees. Ledger only: a returned top-up is neither. {@code billsPending}:
     * the month's payments with bill status PENDING (D-116), whatever the filters.
     */
    public record MonthTotal(String month, BigDecimal added, BigDecimal spent, int billsPending) {
    }

    public record HistoryResponse(
            List<HistoryItem> items,
            List<MonthTotal> monthTotals,
            /** Every Asia/Kolkata month with any history for this outlet, newest first, filters ignored. */
            List<String> availableMonths,
            /** Pass back as {@code cursor} for the next page; null on the last one. */
            String nextCursor,
            /** D-116: the banner's counts, all months, filters ignored. Null on later pages (a cursor was sent). */
            BillSummary billSummary) {
    }

    /** Send wallet money back to the card or bank it came from (D-104). */
    public record WithdrawRequest(
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    /** One payment's share of a withdrawal: a refund to that payment's card. */
    public record WithdrawalPart(
            Long refundId,
            Long paymentId,
            BigDecimal amount,
            String status) {
    }

    public record WithdrawalResponse(
            Long outletId,
            BigDecimal amount,
            BigDecimal balance,
            List<WithdrawalPart> parts,
            /** Payments of the outlet the provider was asked about for this withdrawal; null on a replay of one made before this was recorded. */
            Integer checkedSources,
            /** Payments not asked about (enough was covered first, or the ten-source / twenty-second budget was used); more may be withdrawable in a further step. */
            Integer uncheckedSources) {
    }

    /** Start a Razorpay top-up (D-107). Paise at most; the service checks the range. */
    public record CreateTopUpRequest(
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    /** What the client needs to open Razorpay's checkout for a top-up. */
    public record TopUpResponse(
            Long topUpId,
            String razorpayOrderId,
            /** Razorpay's publishable key id, never a secret. */
            String keyId,
            BigDecimal amount,
            String currency) {
    }

    /**
     * What the checkout handed back. The client is trusted with neither: the
     * signature only shows the payment came out of a checkout for this order, and
     * Razorpay is asked what the payment actually is.
     */
    public record ConfirmTopUpRequest(
            // Letters, digits and underscores (pay_…, mock_pay_…): nothing else ever
            // reaches the provider's URL or our logs (D-101).
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_]+", message = "That isn't a payment id")
            String razorpayPaymentId,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_\\-]+", message = "That isn't a signature")
            String razorpaySignature) {
    }

    /** Where a top-up has got to: CREATED, CREDITED, REFUNDED, FAILED or EXPIRED. */
    public record TopUpStatusResponse(
            Long topUpId,
            Long outletId,
            String status,
            BigDecimal amount,
            String currency,
            String razorpayOrderId,
            Instant creditedAt,
            Instant createdAt) {
    }

    public record TopUpRequest(
            @NotNull @Positive BigDecimal amount,
            @Size(max = 200) String reason) {
    }
}
