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

/** One supplier order's payment. D-010, doc 02 §4. */
@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    /**
     * The checkout this payment belonged to, or null when the order came from an
     * intent.
     *
     * <p>Nullable since V25. A payment is per supplier order (D-010); this groups
     * the payments of one multi-supplier checkout, and an intent-built order has
     * no checkout to group with — one request is one supplier is one order.
     */
    @Column(name = "procurement_id")
    private Long procurementId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "provider_payment_id", length = 200)
    private String providerPaymentId;

    /** The intent the client opens their checkout against. */
    @Column(name = "provider_order_id", length = 200)
    private String providerOrderId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod = "PREPAID";

    /** The full order total — what the supplier will accept is not yet known. */
    @Column(name = "authorized_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal authorizedAmount = BigDecimal.ZERO;

    /** The accepted commercial value (doc 01 §14). */
    @Column(name = "captured_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** Held but never taken. Not a refund — see {@link PaymentStatus#RELEASED}. */
    @Column(name = "released_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal releasedAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    /** When we last agreed with the provider about this payment (doc 21). */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    /** What could still be returned. */
    public BigDecimal refundableAmount() {
        return capturedAmount.subtract(refundedAmount);
    }
}
