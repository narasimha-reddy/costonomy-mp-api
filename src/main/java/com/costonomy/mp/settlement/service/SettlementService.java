package com.costonomy.mp.settlement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.config.AppConfigService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.settlement.domain.*;
import com.costonomy.mp.settlement.repository.*;
import com.costonomy.mp.settlement.web.dto.SettlementDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Settlements. Doc 01 §17, doc 03 §13, doc 09 §11.
 *
 * <p><b>Generation is idempotent.</b> {@code uk_settlement_period} means one
 * settlement per store per period, and a calculation already carrying a
 * {@code settlement_id} is never swept twice. Doc 03 §13 requires reconciliation
 * to be idempotent; generation has to be too, or a re-run pays a supplier twice.
 *
 * <p><b>Approval is a human step.</b> A settlement is money leaving the platform,
 * and the gap between CALCULATED and APPROVED is where somebody reads the number
 * before it becomes a payment. Nothing here approves automatically.
 *
 * <p><b>Figures are frozen at approval.</b> {@code SettlementStatus.isMutable()}
 * stops adjustments after that point — a payout that has been approved is a
 * commitment, and a later correction belongs to the next settlement, as its own
 * adjustment with its own reason.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final SettlementRepository settlements;
    private final SettlementAdjustmentRepository adjustments;
    private final CommissionCalculationRepository calculations;
    private final SettlementNumberGenerator settlementNumbers;
    private final CommissionService commission;
    private final SettlementDirectory directory;
    private final AppConfigService appConfig;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    // ── Generation ───────────────────────────────────────────────────────

    /**
     * Sweep a window's completed orders into settlements, one per store.
     *
     * @return how many settlements were created or extended
     */
    @Transactional
    public int generate(Instant periodStart, Instant periodEnd) {
        int touched = 0;

        for (Long storeId : directory.storesWithSettleableOrders(periodStart, periodEnd)) {
            var orders = directory.settleableOrders(periodStart, periodEnd).stream()
                    .filter(order -> order.supplierStoreId().equals(storeId))
                    .toList();
            if (orders.isEmpty()) {
                continue;
            }

            var settlement = settlements
                    .findBySupplierStoreIdAndPeriodStartAndPeriodEnd(
                            storeId, periodStart, periodEnd)
                    .orElseGet(() -> create(storeId, orders.get(0).supplierOrganizationId(),
                            periodStart, periodEnd));

            if (!settlement.getStatus().isMutable()) {
                // Already approved. New orders belong to the next period rather
                // than reopening a figure somebody has signed off.
                log.info("Settlement {} is {}; {} orders deferred to the next period",
                        settlement.getSettlementNumber(), settlement.getStatus(), orders.size());
                continue;
            }

            for (var order : orders) {
                var calculation = commission.calculate(order);
                if (calculation.getSettlementId() == null) {
                    calculation.setSettlementId(settlement.getId());
                    calculations.save(calculation);
                }
            }

            recompute(settlement);
            settlement.setStatus(SettlementStatus.CALCULATED);
            settlements.save(settlement);

            auditService.record(null, null, "SETTLEMENT_GENERATED", "SETTLEMENT",
                    settlement.getId(), SettlementStatus.PENDING.name(),
                    SettlementStatus.CALCULATED.name(),
                    "%d orders".formatted(settlement.getOrderCount()), "SYSTEM");

            outbox.publish("SettlementGenerated", "SETTLEMENT", settlement.getId(),
                    Map.of("supplierStoreId", storeId,
                            "settlementNumber", settlement.getSettlementNumber(),
                            "netAmount", settlement.getNetAmount().toPlainString()),
                    null);

            touched++;
        }

        return touched;
    }

    private Settlement create(Long storeId, Long organisationId,
                              Instant periodStart, Instant periodEnd) {
        int offsetDays = appConfig.getInt("settlement.offsetDays", 2);

        var settlement = new Settlement();
        settlement.setSettlementNumber(settlementNumbers.next());
        settlement.setSupplierStoreId(storeId);
        settlement.setSupplierOrganizationId(organisationId);
        settlement.setPeriodStart(periodStart);
        settlement.setPeriodEnd(periodEnd);
        // T+2 by default (doc 01 §17), configurable because the doc says so and
        // because banking calendars differ by market.
        settlement.setSettlementDate(
                LocalDate.ofInstant(periodEnd, ZoneOffset.UTC).plusDays(offsetDays));
        return settlements.save(settlement);
    }

    /**
     * Re-total from the lines.
     *
     * <p>Sums the <b>stored</b> figures rather than recalculating commission. The
     * distinction is the whole of doc 05 §33: adding up what was calculated is
     * reproducible, recalculating is a function of today's configuration.
     */
    private void recompute(Settlement settlement) {
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

    // ── Operations ───────────────────────────────────────────────────────

    @Transactional
    public SettlementDtos.SettlementResponse approve(Long actorId, Long settlementId,
                                                     String note) {
        accessControl.require(actorId, Permissions.SETTLEMENT_OPERATE, ScopeType.PLATFORM, null);

        var settlement = load(settlementId);
        transition(settlement, SettlementStatus.APPROVED);
        settlement.setApprovedBy(actorId);
        settlement.setApprovedAt(Instant.now());
        settlements.save(settlement);

        auditService.record(actorId, null, "SETTLEMENT_APPROVED", "SETTLEMENT", settlementId,
                SettlementStatus.CALCULATED.name(), SettlementStatus.APPROVED.name(),
                note, "ADMIN");

        return toResponse(settlement);
    }

    @Transactional
    public SettlementDtos.SettlementResponse markProcessing(Long actorId, Long settlementId) {
        accessControl.require(actorId, Permissions.SETTLEMENT_OPERATE, ScopeType.PLATFORM, null);

        var settlement = load(settlementId);
        transition(settlement, SettlementStatus.PROCESSING);
        settlement.setFailureReason(null);
        settlements.save(settlement);

        auditService.recordTransition(actorId, "SETTLEMENT_PROCESSING", "SETTLEMENT",
                settlementId, SettlementStatus.APPROVED.name(),
                SettlementStatus.PROCESSING.name());
        return toResponse(settlement);
    }

    @Transactional
    public SettlementDtos.SettlementResponse markPaid(Long actorId, Long settlementId,
                                                      String reference) {
        accessControl.require(actorId, Permissions.SETTLEMENT_OPERATE, ScopeType.PLATFORM, null);

        var settlement = load(settlementId);
        if (settlement.getStatus() == SettlementStatus.PAID) {
            // A repeated confirmation of a payment that already landed. Returning
            // the settlement is the true answer; a second PAID would be a second
            // payment as far as any reader is concerned.
            return toResponse(settlement);
        }
        transition(settlement, SettlementStatus.PAID);
        settlement.setPaidAt(Instant.now());
        settlement.setPaymentReference(reference);
        settlements.save(settlement);

        auditService.record(actorId, null, "SETTLEMENT_PAID", "SETTLEMENT", settlementId,
                SettlementStatus.PROCESSING.name(), SettlementStatus.PAID.name(),
                reference, "ADMIN");

        outbox.publish("SettlementPaid", "SETTLEMENT", settlementId,
                Map.of("supplierStoreId", settlement.getSupplierStoreId(),
                        "settlementNumber", settlement.getSettlementNumber(),
                        "netAmount", settlement.getNetAmount().toPlainString()),
                actorId);

        return toResponse(settlement);
    }

    @Transactional
    public SettlementDtos.SettlementResponse markFailed(Long actorId, Long settlementId,
                                                        String reason) {
        accessControl.require(actorId, Permissions.SETTLEMENT_OPERATE, ScopeType.PLATFORM, null);

        var settlement = load(settlementId);
        transition(settlement, SettlementStatus.FAILED);
        settlement.setFailureReason(reason);
        settlements.save(settlement);

        auditService.record(actorId, null, "SETTLEMENT_FAILED", "SETTLEMENT", settlementId,
                SettlementStatus.PROCESSING.name(), SettlementStatus.FAILED.name(),
                reason, "ADMIN");
        return toResponse(settlement);
    }

    /**
     * Add a correction. Doc 01 §17: all adjustments must be auditable.
     *
     * <p>Only while the settlement is still mutable. After approval the figure is
     * a commitment, and a correction belongs to the next settlement — carrying its
     * own reason, rather than silently changing a number somebody signed off.
     */
    @Transactional
    public SettlementDtos.SettlementResponse adjust(Long actorId, Long settlementId,
                                                    SettlementDtos.AddAdjustmentRequest request) {
        accessControl.require(actorId, Permissions.SETTLEMENT_OPERATE, ScopeType.PLATFORM, null);

        var settlement = load(settlementId);
        if (!settlement.getStatus().isMutable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    ("This settlement is %s. Raise the correction against the next one so the "
                            + "approved figure stays as it was.").formatted(settlement.getStatus()));
        }
        if (!"CREDIT".equals(request.direction()) && !"DEBIT".equals(request.direction())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "An adjustment is a CREDIT or a DEBIT.");
        }

        var adjustment = new SettlementAdjustment();
        adjustment.setSettlementId(settlementId);
        adjustment.setDirection(request.direction());
        adjustment.setAmount(request.amount());
        adjustment.setReasonCode(request.reasonCode());
        adjustment.setReason(request.reason());
        adjustment.setSupplierOrderId(request.supplierOrderId());
        adjustment.setCreatedBy(actorId);
        adjustments.save(adjustment);

        recompute(settlement);
        settlements.save(settlement);

        auditService.record(actorId, null, "SETTLEMENT_ADJUSTED", "SETTLEMENT", settlementId,
                null, adjustment.signedAmount().toPlainString(),
                "%s: %s".formatted(request.reasonCode(), request.reason()), "ADMIN");

        return toResponse(settlement);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /** A supplier's own statements. Doc 05 §33. */
    @Transactional(readOnly = true)
    public List<SettlementDtos.SettlementResponse> forStore(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.SETTLEMENT_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        return settlements.findBySupplierStoreIdOrderBySettlementDateDesc(storeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SettlementDtos.SettlementResponse get(Long actorId, Long settlementId) {
        var settlement = load(settlementId);

        // Either the supplier being paid or an operator. A settlement is one
        // supplier's commercial information and nobody else's.
        if (!accessControl.has(actorId, Permissions.SETTLEMENT_VIEW,
                ScopeType.SUPPLIER_STORE, settlement.getSupplierStoreId())
                && !accessControl.has(actorId, Permissions.SETTLEMENT_OPERATE,
                ScopeType.PLATFORM, null)) {
            log.warn("Scope violation: user={} Settlement={} — reported as not found",
                    actorId, settlementId);
            throw new NotFoundException("Settlement", settlementId);
        }

        return toResponse(settlement);
    }

    @Transactional(readOnly = true)
    public List<SettlementDtos.SettlementResponse> search(Long actorId, SettlementStatus status) {
        accessControl.require(actorId, Permissions.SETTLEMENT_OPERATE, ScopeType.PLATFORM, null);

        var found = status == null
                ? settlements.findAll() : settlements.findByStatusOrderByIdAsc(status);
        return found.stream().map(this::toResponse).toList();
    }

    // ── internals ────────────────────────────────────────────────────────

    private Settlement load(Long settlementId) {
        return settlements.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("Settlement", settlementId));
    }

    private void transition(Settlement settlement, SettlementStatus target) {
        if (!settlement.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A settlement can't move from %s to %s."
                            .formatted(settlement.getStatus(), target));
        }
        settlement.setStatus(target);
    }

    private SettlementDtos.SettlementResponse toResponse(Settlement settlement) {
        List<SettlementDtos.SettlementLineResponse> lines = new ArrayList<>();
        for (var calculation : calculations.findBySettlementId(settlement.getId())) {
            var orderNumber = jdbc.queryForList(
                    "select order_number from supplier_order where id = ?",
                    String.class, calculation.getSupplierOrderId());
            lines.add(new SettlementDtos.SettlementLineResponse(
                    calculation.getSupplierOrderId(),
                    orderNumber.isEmpty() ? null : orderNumber.get(0),
                    calculation.getGrossAmount(), calculation.getRatePercent(),
                    calculation.getCommissionAmount(), calculation.getNetAmount(),
                    calculation.getCalculatedAt()));
        }

        var adjustmentResponses = adjustments
                .findBySettlementIdOrderByIdAsc(settlement.getId()).stream()
                .map(adjustment -> new SettlementDtos.AdjustmentResponse(
                        adjustment.getId(), adjustment.getDirection(), adjustment.getAmount(),
                        adjustment.getReasonCode(), adjustment.getReason(),
                        adjustment.getSupplierOrderId(), adjustment.getCreatedAt()))
                .toList();

        return new SettlementDtos.SettlementResponse(
                settlement.getId(), settlement.getSettlementNumber(),
                settlement.getSupplierStoreId(), settlement.getStatus(),
                settlement.getPeriodStart(), settlement.getPeriodEnd(),
                settlement.getSettlementDate(), settlement.getGrossAmount(),
                settlement.getCommissionAmount(), settlement.getAdjustmentAmount(),
                settlement.getNetAmount(), settlement.getOrderCount(),
                settlement.getApprovedAt(), settlement.getPaidAt(),
                settlement.getPaymentReference(), settlement.getFailureReason(),
                lines, adjustmentResponses);
    }
}
