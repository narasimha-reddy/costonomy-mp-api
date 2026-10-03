package com.costonomy.mp.settlement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What an approved dispute refund takes from a supplier's payout (D-104).
 *
 * <p>PENDING until the order is in a settlement, then APPLIED to it as a DEBIT
 * adjustment. Costonomy never funds a refund; this row is how that is kept.
 */
@Entity
@Table(name = "supplier_deduction")
@Getter
@Setter
@NoArgsConstructor
public class SupplierDeduction extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "dispute_refund_id", nullable = false)
    private Long disputeRefundId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "settlement_adjustment_id")
    private Long settlementAdjustmentId;

    @Column(name = "applied_at")
    private Instant appliedAt;
}
