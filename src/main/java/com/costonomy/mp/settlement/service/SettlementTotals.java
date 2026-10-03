package com.costonomy.mp.settlement.service;

import com.costonomy.mp.settlement.domain.Settlement;
import com.costonomy.mp.settlement.repository.CommissionCalculationRepository;
import com.costonomy.mp.settlement.repository.SettlementAdjustmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * A settlement's totals, from its lines. Its own bean so that the settlement
 * service and the refund ledger (D-104) can both re-total without depending on
 * each other.
 */
@Component
@RequiredArgsConstructor
public class SettlementTotals {

    private final CommissionCalculationRepository calculations;
    private final SettlementAdjustmentRepository adjustments;

    /**
     * Re-total from the lines.
     *
     * <p>Sums the <b>stored</b> figures rather than recalculating commission. The
     * distinction is the whole of doc 05 §33: adding up what was calculated is
     * reproducible, recalculating is a function of today's configuration.
     */
    public void recompute(Settlement settlement) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commissionTotal = BigDecimal.ZERO;
        int count = 0;

        for (var calculation : calculations.findBySettlementId(settlement.getId())) {
            gross = gross.add(calculation.getGrossAmount());
            commissionTotal = commissionTotal.add(calculation.getCommissionAmount());
            count++;
        }

        BigDecimal adjustmentTotal = BigDecimal.ZERO;
        for (var adjustment : adjustments.findBySettlementIdOrderByIdAsc(settlement.getId())) {
            adjustmentTotal = adjustmentTotal.add(adjustment.signedAmount());
        }

        settlement.setGrossAmount(gross);
        settlement.setCommissionAmount(commissionTotal);
        settlement.setAdjustmentAmount(adjustmentTotal);
        // Gross - Commission ± Adjustments = Net. Doc 01 §17.
        settlement.setNetAmount(gross.subtract(commissionTotal).add(adjustmentTotal));
        settlement.setOrderCount(count);
    }
}
