package com.costonomy.mp.wallet.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One attempt to add money to a wallet through Razorpay (D-107).
 *
 * <p><b>Its status is moved by conditional updates, not by saving this entity.</b>
 * {@code WalletTopUpRepository} carries the transitions, each guarded by the
 * status it expects, because the transition that credits the wallet has to be
 * the one place two racing callers are told apart. The setters here are for
 * creating the row; nothing loads one, changes a status and saves it.
 */
@Entity
@Table(name = "wallet_top_up")
@Getter
@Setter
@NoArgsConstructor
public class WalletTopUp extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private WalletTopUpStatus status = WalletTopUpStatus.CREATED;

    @Column(name = "razorpay_order_id", length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(name = "provider_refund_id", length = 64)
    private String providerRefundId;

    @Column(name = "refund_attempts", nullable = false)
    private int refundAttempts;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Column(name = "checked_at")
    private Instant checkedAt;
}
