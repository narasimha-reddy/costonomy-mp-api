package com.costonomy.mp.wallet.web.dto;

import com.costonomy.mp.wallet.domain.WalletDirection;
import jakarta.validation.constraints.Digits;
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
            List<EntryResponse> recent) {
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

    public record TopUpRequest(
            @NotNull @Positive BigDecimal amount,
            @Size(max = 200) String reason) {
    }
}
