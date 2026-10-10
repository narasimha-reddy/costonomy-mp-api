package com.costonomy.mp.credit.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What Mandi owes a supplier for one wallet repayment. D-156.
 *
 * <p>The restaurant's wallet money left the wallet (D-152), so Mandi holds it for the supplier. The row is
 * {@link #PENDING} from the repayment's transaction until a settlement for the store applies it as adjustments,
 * then {@link #APPLIED}. The commission figures are a snapshot taken when the repayment was made.
 */
@Entity
@Table(name = "credit_repayment_payout")
@Getter
@Setter
@NoArgsConstructor
public class CreditRepaymentPayout extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";

    @Column(name = "credit_repayment_id", nullable = false)
    private Long creditRepaymentId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** The store's rate when the repayment was made; null when none applied or the switch was off. */
    @Column(name = "commission_rate_percent", precision = 9, scale = 4)
    private BigDecimal commissionRatePercent;

    @Column(name = "commission_configuration_id")
    private Long commissionConfigurationId;

    @Column(name = "commission_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "credit_adjustment_id")
    private Long creditAdjustmentId;

    @Column(name = "commission_adjustment_id")
    private Long commissionAdjustmentId;

    @Column(name = "applied_at")
    private Instant appliedAt;
}
