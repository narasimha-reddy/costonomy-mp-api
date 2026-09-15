package com.costonomy.mp.settlement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.settlement.domain.CommissionCalculation;
import com.costonomy.mp.settlement.repository.CommissionCalculationRepository;
import com.costonomy.mp.settlement.repository.SettlementRepository;
import com.costonomy.mp.settlement.web.dto.SettlementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Checks a settlement against what restaurants actually paid. Doc 09 §11, doc 03 §13.
 *
 * <p>Two independent records of the same money: the settlement says what a
 * supplier is owed, derived from order records; the payment tables say what was
 * captured, derived from the provider. They should agree, and when they do not,
 * something has already gone wrong — a capture that failed silently, a refund
 * nobody accounted for, or an order settled that was never paid.
 *
 * <p><b>A mismatch is recorded, not thrown.</b> Doc 03 §13 requires reconciliation
 * to be idempotent, and refusing to complete would make a discrepancy block every
 * later run rather than surface for a human. The settlement carries the last
 * answer, an operator sees it, and the payout waits at APPROVED where it already
 * was — which is the correct place for money that nobody has explained yet.
 *
 * <p><b>Refunds are netted off.</b> Doc 09 §11 requires refunds to be linked to
 * their original payment, which they are; here that means captured minus refunded,
 * because a refunded order's money is not the platform's to pass on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementReconciliationService {

    private final SettlementRepository settlements;
    private final CommissionCalculationRepository calculations;
    private final SettlementDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;

    @Transactional
    public SettlementDtos.ReconciliationResponse reconcile(Long actorId, Long settlementId) {
        if (actorId != null) {
            accessControl.require(actorId, Permissions.PAYMENT_RECONCILE,
                    ScopeType.PLATFORM, null);
        }
        return reconcileInternal(settlementId, actorId);
    }

    /**
     * Reconcile without an actor — the scheduled sweep.
     *
     * <p>Safe to run repeatedly: it recomputes from the same two sources and
     * overwrites its own last answer. Nothing accumulates, so a settlement
     * reconciled a hundred times looks exactly like one reconciled once.
     */
    @Transactional
    public SettlementDtos.ReconciliationResponse reconcileInternal(Long settlementId,
                                                                   Long actorId) {
        var settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("Settlement", settlementId));

        var orderIds = calculations.findBySettlementId(settlementId).stream()
                .map(CommissionCalculation::getSupplierOrderId)
                .toList();

        BigDecimal captured = directory.capturedFor(orderIds);
        BigDecimal difference = settlement.getGrossAmount().subtract(captured);
        // compareTo, never equals: 4000.00 and 4000.0000 are the same money and
        // equals would call them a discrepancy.
        boolean matched = difference.compareTo(BigDecimal.ZERO) == 0;

        String note = matched
                ? "Settlement gross matches captured payments."
                : ("Settlement gross %s does not match captured %s — difference %s. "
                        + "Check for a failed capture or an unaccounted refund.")
                        .formatted(settlement.getGrossAmount(), captured, difference);

        settlement.setReconciledAt(Instant.now());
        settlement.setReconciledGross(captured);
        settlement.setReconciliationNote(note);
        settlements.save(settlement);

        if (!matched) {
            // Loud, because this is money. Doc 09 §11's whole point is that a
            // discrepancy is findable rather than absorbed.
            log.error("Settlement {} does not reconcile: gross {} vs captured {}",
                    settlement.getSettlementNumber(), settlement.getGrossAmount(), captured);
            auditService.record(actorId, null, "SETTLEMENT_RECONCILIATION_MISMATCH",
                    "SETTLEMENT", settlementId, settlement.getGrossAmount().toPlainString(),
                    captured.toPlainString(), note, actorId == null ? "SYSTEM" : "ADMIN");
        }

        return new SettlementDtos.ReconciliationResponse(settlementId, matched,
                settlement.getGrossAmount(), captured, difference, note,
                settlement.getReconciledAt());
    }
}
