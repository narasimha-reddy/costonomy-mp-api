package com.costonomy.mp.payment.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** Money returned after capture. Doc 03 §7, doc 22. */
@Entity
@Table(name = "refund")
@Getter
@Setter
@NoArgsConstructor
public class Refund extends BaseEntity {

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason", nullable = false, length = 64)
    private RefundReason reason;

    @Column(name = "note", length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(name = "provider_refund_id", length = 200)
    private String providerRefundId;

    /**
     * Uniquely indexed. Doc 22: refunds must be idempotent, and a duplicate is
     * money leaving twice — the constraint decides it rather than a check that
     * would race with the retry it is meant to catch.
     */
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "completed_at")
    private Instant completedAt;
}
