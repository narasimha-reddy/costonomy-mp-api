package com.costonomy.mp.settlement.web.dto;

import com.costonomy.mp.settlement.domain.SettlementStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SettlementDtos {

    private SettlementDtos() {
    }

    /**
     * A supplier's statement. Doc 05 §33, §23A.33.
     *
     * <p>Every line of {@code Gross - Commission ± Adjustments = Net} is present,
     * because that is the arithmetic the supplier is checking. A response carrying
     * only the net would make "why is this number what it is" unanswerable without
     * a support call.
     */
    public record SettlementResponse(
            Long id,
            String settlementNumber,
            Long supplierStoreId,
            SettlementStatus status,
            Instant periodStart,
            Instant periodEnd,
            LocalDate settlementDate,
            BigDecimal grossAmount,
            BigDecimal commissionAmount,
            BigDecimal adjustmentAmount,
            BigDecimal netAmount,
            int orderCount,
            Instant approvedAt,
            Instant paidAt,
            String paymentReference,
            String failureReason,
            List<SettlementLineResponse> lines,
            List<AdjustmentResponse> adjustments) {
    }

    /**
     * One order's contribution.
     *
     * @param ratePercent the rate that applied when this was calculated — not the
     *                    current one. Doc 05 §33: historical settlement must not
     *                    depend on current configuration.
     */
    public record SettlementLineResponse(
            Long supplierOrderId,
            String orderNumber,
            BigDecimal grossAmount,
            BigDecimal ratePercent,
            BigDecimal commissionAmount,
            BigDecimal netAmount,
            Instant calculatedAt) {
    }

    public record AdjustmentResponse(
            Long id,
            String direction,
            BigDecimal amount,
            String reasonCode,
            String reason,
            Long supplierOrderId,
            Instant createdAt) {
    }

    // ── Operations ───────────────────────────────────────────────────────

    public record ApproveSettlementRequest(
            @Size(max = 500) String note,
            /** Required to approve a settlement whose payments do not reconcile. */
            @Size(max = 500) String acknowledgeMismatchNote) {
    }

    public record MarkPaidRequest(
            @NotBlank(message = "Give the payment reference") @Size(max = 200) String reference) {
    }

    public record AddAdjustmentRequest(
            @NotBlank(message = "CREDIT or DEBIT") String direction,
            @NotNull @DecimalMin(value = "0.01", message = "More than zero") BigDecimal amount,
            @NotBlank(message = "Choose a reason code") String reasonCode,
            @NotBlank(message = "Say why") @Size(max = 500) String reason,
            Long supplierOrderId) {
    }

    /**
     * @param matched         whether the settlement's gross equals what was
     *                        actually captured from restaurants
     * @param difference      settlement gross minus captured. Non-zero is the first
     *                        sign a capture failed silently or a refund went
     *                        unaccounted for.
     * @param creditRepaymentsMatched whether, for the settlement's period, the wallet money repaid
     *                        as credit equals what is recorded as owed to suppliers for it (D-156).
     *                        Separate from {@code matched}, which stays about the gross.
     * @param repaymentWalletDebits    the wallet CREDIT_REPAYMENT debits in the period
     * @param repaymentPayoutsOwed     the payouts recorded for those repayments
     */
    public record ReconciliationResponse(
            Long settlementId,
            boolean matched,
            BigDecimal settlementGross,
            BigDecimal capturedGross,
            BigDecimal difference,
            String note,
            Instant reconciledAt,
            boolean creditRepaymentsMatched,
            BigDecimal repaymentWalletDebits,
            BigDecimal repaymentPayoutsOwed) {
    }

    // ── Direct Delivery Funding Payments (D-203) ──────────────────────────

    public record PendingDeliveryFundingResponse(
            Long deductionId,
            Long supplierOrderId,
            String orderNumber,
            Long deliveryId,
            BigDecimal amount,
            String reason,
            String status,
            String paymentMethod,
            String providerOrderId,
            Instant createdAt) {
    }

    /**
     * One delivery-funding line on a supplier's statement, in any state. {@code outcome} says in plain terms where the
     * money stands; {@code nextAction} says what, if anything, the supplier does. A credit (a correction in the
     * supplier's favour) has a negative effect and is flagged by {@code kind}. This is what the supplier owes
     * Costonomy for a delivery it chose to fund; it is never a payment to the courier.
     */
    public record DeliveryFundingLine(
            Long deductionId,
            String kind,
            Long supplierOrderId,
            String orderNumber,
            Long deliveryId,
            BigDecimal amount,
            String status,
            String outcome,
            String nextAction,
            String paymentMethod,
            Long settlementId,
            Instant appliedAt,
            Instant paidAt,
            String reason,
            Instant createdAt) {
    }

    /** The supplier's delivery funding in four buckets that never double count: owed, paid directly, taken from payouts, waived. */
    public record DeliveryFundingSummary(
            BigDecimal pending,
            BigDecimal collectedDirectly,
            BigDecimal appliedToSettlement,
            BigDecimal waived,
            BigDecimal creditsPending,
            BigDecimal creditsApplied,
            long pendingCount) {
    }

    public record InitiateDeliveryFundingPaymentRequest(
            String clientKey) {
    }

    public record InitiateDeliveryFundingPaymentResponse(
            Long deductionId,
            String razorpayOrderId,
            String keyId,
            BigDecimal amount,
            String currency) {
    }

    public record ConfirmDeliveryFundingPaymentRequest(
            @NotBlank(message = "Payment id is required") String paymentId,
            @NotBlank(message = "Signature is required") String signature) {
    }

    public record ConfirmDeliveryFundingPaymentResponse(
            Long deductionId,
            String status,
            String paymentId,
            BigDecimal amount,
            Instant paidAt) {
    }
}
