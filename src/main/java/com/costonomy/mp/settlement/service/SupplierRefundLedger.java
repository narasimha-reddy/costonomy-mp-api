package com.costonomy.mp.settlement.service;

import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.settlement.domain.Settlement;
import com.costonomy.mp.settlement.domain.SettlementAdjustment;
import com.costonomy.mp.settlement.domain.SupplierDeduction;
import com.costonomy.mp.settlement.repository.CommissionCalculationRepository;
import com.costonomy.mp.settlement.repository.SettlementAdjustmentRepository;
import com.costonomy.mp.settlement.repository.SettlementRepository;
import com.costonomy.mp.settlement.repository.SupplierDeductionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * A supplier pays for every refund on their order, out of their payout for it
 * (D-104). Costonomy never funds one.
 *
 * <p><b>A refund is only possible while that payout is still ours to change.</b>
 * The most a restaurant can get back is what the supplier would be paid for the
 * order — its value less commission, which Costonomy keeps — less anything
 * already taken for earlier refunds. And only until the settlement carrying the
 * order is approved: after that the money is committed to the supplier, and a
 * refund would come out of Costonomy's pocket if the supplier never traded again.
 *
 * <p>The other half of the guarantee is in {@link SettlementService#approve}: a
 * payout cannot be approved while a refund on one of its orders is undecided, so
 * a deduction can never arrive after the figure it belongs to was signed off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierRefundLedger {

    /** How long an opened checkout keeps its charge out of a settlement. */
    private static final java.time.Duration CHECKOUT_CLAIM = java.time.Duration.ofMinutes(30);

    /** Where the goods have arrived. Earlier, a problem is a cancellation, not a refund. */
    private static final Set<String> REFUNDABLE_STATES = Set.of("DELIVERED", "COMPLETED");

    private final SettlementDirectory directory;
    private final CommissionService commission;
    private final CommissionCalculationRepository calculations;
    private final SettlementRepository settlements;
    private final SettlementAdjustmentRepository adjustments;
    private final SupplierDeductionRepository deductions;
    private final SettlementTotals totals;
    private final AuditService auditService;

    /**
     * How much of this order's payout could still go back to the restaurant.
     *
     * @param available what is left, when {@code refusal} is null
     * @param refusal   why nothing can be refunded, in words for the person asking
     */
    public record Coverage(BigDecimal available, String refusal) {
        public boolean refused() {
            return refusal != null;
        }
    }

    @Transactional(readOnly = true)
    public Coverage coverage(Long supplierOrderId) {
        var figures = directory.orderFigures(supplierOrderId).orElse(null);
        if (figures == null) {
            return new Coverage(BigDecimal.ZERO, "That order doesn't exist.");
        }
        if (!REFUNDABLE_STATES.contains(figures.status())) {
            return new Coverage(BigDecimal.ZERO,
                    "A refund can be asked for once the order has been delivered.");
        }

        BigDecimal net;
        var calculation = calculations.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (calculation != null) {
            if (calculation.getSettlementId() != null) {
                var settlement = settlements.findById(calculation.getSettlementId()).orElseThrow();
                if (!settlement.getStatus().isMutable()) {
                    return new Coverage(BigDecimal.ZERO,
                            "The supplier has already been paid for this order, so it can't be "
                                    + "refunded here. Please contact support.");
                }
            }
            net = calculation.getNetAmount();
        } else {
            // Not settled yet: what the calculation would say now. The rate is the
            // one in force today, which is the one generation will use unless it
            // changes in the day between (D-104's note on that).
            net = commission.preview(figures.order()).net();
        }

        BigDecimal left = net.subtract(deductions.sumForOrder(supplierOrderId)).max(BigDecimal.ZERO);
        return new Coverage(left, null);
    }

    /**
     * Take an approved refund from the supplier's payout for the order.
     *
     * <p>Idempotent on the request: a second call returns the first deduction. In
     * the caller's transaction, with the refund to the restaurant, so the two
     * happen together or not at all.
     */
    @Transactional
    public SupplierDeduction charge(Long supplierOrderId, Long disputeRefundId, BigDecimal amount,
                                    String reason, Long actorId) {
        var existing = deductions.findByDisputeRefundId(disputeRefundId);
        if (existing.isPresent()) {
            return existing.get();
        }

        var coverage = coverage(supplierOrderId);
        if (coverage.refused()) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, coverage.refusal());
        }
        if (amount.compareTo(coverage.available()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "At most ₹%s of this order can be refunded.".formatted(Rupees.of(coverage.available())));
        }

        var figures = directory.orderFigures(supplierOrderId).orElseThrow();
        var deduction = new SupplierDeduction();
        deduction.setSupplierStoreId(figures.order().supplierStoreId());
        deduction.setSupplierOrderId(supplierOrderId);
        deduction.setDisputeRefundId(disputeRefundId);
        deduction.setAmount(amount);
        deduction.setReason(reason);
        deductions.saveAndFlush(deduction);

        // Already in an open settlement: taken from it now. Otherwise it waits for
        // the settlement that picks the order up.
        var calculation = calculations.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (calculation != null && calculation.getSettlementId() != null) {
            var settlement = settlements.findById(calculation.getSettlementId()).orElseThrow();
            apply(deduction, settlement);
            totals.recompute(settlement);
            // Versioned: an approval of this settlement at the same moment fails one
            // of the two, so the deduction cannot slip in under a signed-off figure.
            settlements.save(settlement);
        }

        auditService.record(actorId, null, "SUPPLIER_DEDUCTION_RECORDED", "SUPPLIER_ORDER",
                supplierOrderId, null, deduction.getStatus(),
                "%s for dispute refund %d".formatted(amount.toPlainString(), disputeRefundId), "API");
        log.info("Deduction of {} from the payout for order {} (dispute refund {}) recorded as {}",
                amount.toPlainString(), supplierOrderId, disputeRefundId, deduction.getStatus());
        return deduction;
    }

    /**
     * Take any deductions still waiting on these orders into this settlement. The
     * caller re-totals it.
     *
     * @return how many were applied
     */
    @Transactional
    public int applyPending(Settlement settlement, Collection<Long> orderIds) {
        // Charges on this settlement's orders, if it has any. A settlement with none (made only to pay a credit owed to the
        // store) still takes the store's credits below.
        var pending = new java.util.ArrayList<SupplierDeduction>();
        if (!orderIds.isEmpty()) {
            pending.addAll(deductions.findByStatusAndSupplierOrderIdIn(SupplierDeduction.PENDING, orderIds));
        }
        // Funded deliveries on credit orders, which no settlement of their own will ever pick up (D-203).
        deductions.findPendingCreditDeliveryFunding(settlement.getSupplierStoreId()).stream()
                .filter(d -> pending.stream().noneMatch(p -> p.getId().equals(d.getId())))
                .forEach(pending::add);
        // Credits for charges the courier's final cost did not need (D-204): the store's, whichever order they came from.
        deductions.findPendingCredits(settlement.getSupplierStoreId()).stream()
                .filter(d -> pending.stream().noneMatch(p -> p.getId().equals(d.getId())))
                .forEach(pending::add);
        // A charge whose gateway checkout was opened a moment ago is the supplier paying it right now: taking it from the
        // payout as well would charge them twice. It waits for the next settlement if they do not finish (D-203).
        Instant claimed = Instant.now().minus(CHECKOUT_CLAIM);
        pending.removeIf(d -> SupplierDeduction.KIND_DELIVERY_FUNDING.equals(d.getKind())
                && d.getProviderOrderId() != null && d.getPaidAt() == null
                && d.getCheckoutOpenedAt() != null && d.getCheckoutOpenedAt().isAfter(claimed));
        pending.forEach(deduction -> apply(deduction, settlement));
        return pending.size();
    }

    /** Stores that are owed a credit, so a settlement is generated for them even with no order to settle (D-204). */
    public java.util.List<Long> storesWithPendingCredits() {
        return deductions.storesWithPendingCredits();
    }

    private void apply(SupplierDeduction deduction, Settlement settlement) {
        var adjustment = new SettlementAdjustment();
        adjustment.setSettlementId(settlement.getId());
        boolean credit = SupplierDeduction.KIND_DELIVERY_FUNDING_CREDIT.equals(deduction.getKind());
        adjustment.setDirection(credit ? "CREDIT" : "DEBIT");
        adjustment.setAmount(deduction.getAmount());
        boolean delivery = SupplierDeduction.KIND_DELIVERY_FUNDING.equals(deduction.getKind());
        adjustment.setReasonCode(credit ? "DELIVERY_FUNDING_CORRECTION" : delivery ? "DELIVERY_FUNDING" : "REFUND");
        adjustment.setReason(credit ? "Delivery share returned: " + deduction.getReason()
                : delivery ? "Delivery paid by you: " + deduction.getReason()
                : "Refund to the restaurant: " + deduction.getReason());
        adjustment.setSupplierOrderId(deduction.getSupplierOrderId());
        adjustments.save(adjustment);

        deduction.setStatus(SupplierDeduction.APPLIED);
        deduction.setSettlementId(settlement.getId());
        deduction.setSettlementAdjustmentId(adjustment.getId());
        deduction.setAppliedAt(Instant.now());
        deductions.save(deduction);
        log.info("Deduction {} of {} applied to settlement {}", deduction.getId(),
                deduction.getAmount().toPlainString(), settlement.getSettlementNumber());
    }
}
