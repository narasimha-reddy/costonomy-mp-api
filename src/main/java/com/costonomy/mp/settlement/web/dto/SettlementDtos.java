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
            @Size(max = 500) String note) {
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
     */
    public record ReconciliationResponse(
            Long settlementId,
            boolean matched,
            BigDecimal settlementGross,
            BigDecimal capturedGross,
            BigDecimal difference,
            String note,
            Instant reconciledAt) {
    }
}
