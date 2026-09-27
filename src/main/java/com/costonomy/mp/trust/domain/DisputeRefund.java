package com.costonomy.mp.trust.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** A refund asked for on a dispute, and who decided it. D-104. */
@Entity
@Table(name = "dispute_refund")
@Getter
@Setter
@NoArgsConstructor
public class DisputeRefund extends BaseEntity {

    @Column(name = "dispute_id", nullable = false)
    private Long disputeId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private DisputeRefundStatus status = DisputeRefundStatus.REQUESTED;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "supplier_decided_by")
    private Long supplierDecidedBy;

    @Column(name = "supplier_decided_at")
    private Instant supplierDecidedAt;

    @Column(name = "supplier_note", length = 500)
    private String supplierNote;

    @Column(name = "ops_decided_by")
    private Long opsDecidedBy;

    @Column(name = "ops_decided_at")
    private Instant opsDecidedAt;

    @Column(name = "ops_note", length = 500)
    private String opsNote;

    /** The wallet refund an approval made, for a card-paid order. */
    @Column(name = "refund_id")
    private Long refundId;
}
