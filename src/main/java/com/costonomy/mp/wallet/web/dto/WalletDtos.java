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
            Instant at) {
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
