package com.costonomy.mp.credit.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Money from a captured credit payment that goes back to the card or UPI it came from (D-196). Created in the
 * transaction that applies (or refuses) the capture; sent by a job, whose conditional updates move it on.
 */
@Entity
@Table(name = "credit_payment_refund")
@Getter
@Setter
@NoArgsConstructor
public class CreditPaymentRefund extends BaseEntity {

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "razorpay_payment_id", nullable = false, length = 64)
    private String razorpayPaymentId;

    /** ORIGINAL (a Razorpay refund to the source). WALLET is reserved for a later ops undo; v1 never writes it. */
    @Column(name = "destination", nullable = false, length = 16)
    private String destination = "ORIGINAL";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason", nullable = false, length = 32)
    private CreditPayRefundReason reason;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private CreditPayRefundStatus status = CreditPayRefundStatus.PENDING;

    @Column(name = "provider_refund_id", length = 64)
    private String providerRefundId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_tried_at")
    private Instant lastTriedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;
}
