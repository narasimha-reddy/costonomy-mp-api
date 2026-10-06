package com.costonomy.mp.credit.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the supplier's payouts screen reads (S8a). Every figure is computed on the server. */
public final class CreditPayoutDtos {

    private CreditPayoutDtos() {
    }

    /** One invoice a payout settled, with the part of the repayment that went to it. */
    public record PayoutInvoice(Long invoiceId, String invoiceNumber, BigDecimal amount) {
    }

    /**
     * One wallet repayment Mandi collected for the store. {@code commissionAmount} and {@code commissionRatePercent}
     * are the snapshot taken when the repayment was made (null rate: none applied). The settlement fields are null
     * while the payout is PENDING.
     */
    public record PayoutResponse(
            Long payoutId,
            Long repaymentId,
            Long agreementId,
            Long outletId,
            String outletName,
            String restaurantName,
            BigDecimal grossAmount,
            BigDecimal commissionRatePercent,
            BigDecimal commissionAmount,
            BigDecimal netAmount,
            String status,
            Long settlementId,
            String settlementNumber,
            LocalDate settlementDate,
            Instant appliedAt,
            Instant createdAt,
            List<PayoutInvoice> invoices) {
    }

    /** The store's position, whatever filter or page the list is on. */
    public record PayoutSummary(BigDecimal pendingNet, BigDecimal appliedNetThisMonth) {
    }

    public record PayoutListResponse(
            PayoutSummary summary,
            List<PayoutResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext) {
    }
}
