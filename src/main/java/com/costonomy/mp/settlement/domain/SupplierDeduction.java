package com.costonomy.mp.settlement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What is taken from a supplier's payout for an order: an approved dispute refund (D-104), or the supplier's share of
 * a Costonomy delivery they chose to pay for (D-203).
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

    public static final String KIND_DISPUTE_REFUND = "DISPUTE_REFUND";
    public static final String KIND_DELIVERY_FUNDING = "DELIVERY_FUNDING";

    /** Which of {@code disputeRefundId} or {@code deliveryId} says why. */
    @Column(name = "kind", nullable = false, length = 24)
    private String kind = KIND_DISPUTE_REFUND;

    @Column(name = "delivery_id")
    private Long deliveryId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "dispute_refund_id")
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
