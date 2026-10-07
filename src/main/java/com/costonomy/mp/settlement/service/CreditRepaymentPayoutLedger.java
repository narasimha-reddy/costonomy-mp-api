package com.costonomy.mp.settlement.service;

import com.costonomy.mp.settlement.domain.Settlement;
import com.costonomy.mp.settlement.domain.SettlementAdjustment;
import com.costonomy.mp.settlement.repository.SettlementAdjustmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * What a restaurant repaid from its wallet is paid to the supplier through the settlement (D-156).
 *
 * <p>Mandi took that money out of the restaurant's wallet (D-152), so it owes it to the supplier. A
 * {@code credit_repayment_payout} row records the debt, PENDING; the settlement for the store applies it as a
 * CREDIT adjustment for the amount and, when the commission snapshot is above zero, a DEBIT adjustment for the
 * commission. The settlement total stays one rule ({@link SettlementTotals}): gross - commission +/- adjustments.
 *
 * <p>Modelled on {@link SupplierRefundLedger#applyPending}: the rows are locked {@code FOR UPDATE} so two
 * generations at once apply each payout once, a payout is applied only while the settlement is still mutable, and
 * one that finds the settlement approved stays PENDING for the next. Read and written in SQL because the table is
 * the credit module's, the same boundary rule as {@link SettlementDirectory}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditRepaymentPayoutLedger {

    private final JdbcTemplate jdbc;
    private final SettlementAdjustmentRepository adjustments;

    /** Stores with a payout still pending, made before {@code to}. */
    public List<Long> storesWithPending(Instant to) {
        return jdbc.queryForList("""
                select distinct supplier_store_id from credit_repayment_payout
                 where status = 'PENDING' and created_at < ?
                """, Long.class, Timestamp.from(to));
    }

    public Long organisationOf(Long storeId) {
        return jdbc.queryForObject("select supplier_organization_id from supplier_store where id = ?",
                Long.class, storeId);
    }

    /**
     * Apply this store's pending payouts made before {@code to} to the settlement. The caller re-totals it.
     *
     * @return how many were applied; 0 when the settlement is no longer mutable
     */
    @Transactional
    public int applyPending(Settlement settlement, Instant to) {
        if (!settlement.getStatus().isMutable()) {
            // Approved or later: the figure is a commitment. The payout waits for the next settlement.
            return 0;
        }
        var rows = jdbc.query("""
                select id, credit_repayment_id, amount, commission_amount
                  from credit_repayment_payout
                 where supplier_store_id = ? and status = 'PENDING' and created_at < ?
                 order by id
                   for update
                """,
                (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
                settlement.getSupplierStoreId(), Timestamp.from(to));

        for (var row : rows) {
            Long creditId = adjust(settlement, "CREDIT", row.amount(), "CREDIT_REPAYMENT",
                    "Restaurant repaid credit from its wallet (repayment %d)".formatted(row.repaymentId()));
            Long commissionId = null;
            if (row.commission().signum() > 0) {
                commissionId = adjust(settlement, "DEBIT", row.commission(), "CREDIT_COMMISSION",
                        "Commission on the wallet repayment (repayment %d)".formatted(row.repaymentId()));
            }
            jdbc.update("""
                    update credit_repayment_payout
                       set status = 'APPLIED', settlement_id = ?, credit_adjustment_id = ?,
                           commission_adjustment_id = ?, applied_at = CURRENT_TIMESTAMP(6), version = version + 1
                     where id = ? and status = 'PENDING'
                    """, settlement.getId(), creditId, commissionId, row.id());
            log.info("Credit repayment payout {} of {} applied to settlement {}", row.id(),
                    row.amount().toPlainString(), settlement.getSettlementNumber());
        }
        return rows.size();
    }

    private Long adjust(Settlement settlement, String direction, BigDecimal amount, String code, String reason) {
        var adjustment = new SettlementAdjustment();
        adjustment.setSettlementId(settlement.getId());
        adjustment.setDirection(direction);
        adjustment.setAmount(amount);
        adjustment.setReasonCode(code);
        adjustment.setReason(reason);
        return adjustments.saveAndFlush(adjustment).getId();
    }

    private record Row(long id, long repaymentId, BigDecimal amount, BigDecimal commission) {
    }
}
