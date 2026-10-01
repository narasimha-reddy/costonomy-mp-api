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

    /**
     * How the payer paid, as Razorpay names it: {@code card}, {@code upi},
     * {@code netbanking}, {@code wallet}, {@code emi}, {@code paylater}. Null until
     * we have read it from the provider. Decides what cancelling the order does
     * (D-109): only a card is a hold that may lapse; anything else, or not knowing,
     * is money already debited and is sent back.
     */
    @Column(name = "provider_method", length = 32)
    private String providerMethod;

    /** A card's last four digits or a wallet's name. Never a UPI address or an account. */
    @Column(name = "provider_method_detail", length = 64)
    private String providerMethodDetail;

    /**
     * What Razorpay kept on capture, in rupees: its {@code fee}, which already includes
     * the GST on it. Null until a capture reported it.
     */
    @Column(name = "provider_fee", precision = 19, scale = 4)
    private BigDecimal providerFee;

    /**
     * How long, in minutes, the provider was told to hold this payment's authorisation,
     * fixed when its order was created (D-109). The guard on "ready" reads this, not the
     * setting, so changing the setting cannot lengthen the hold of a payment made
     * before. Null on a payment from before it was stored: it uses the current setting.
     */
    @Column(name = "hold_minutes")
    private Integer holdMinutes;

    /** The order was cancelled. Set whatever state the payment was in. */
    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    /** Runs of the cancellation job on this payment, for the alert on one that will not finish. */
    @Column(name = "cancel_attempts", nullable = false)
    private int cancelAttempts;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "release_reason", length = 32)
    private ReleaseReason releaseReason;

    /** A person has to look. The cancellation job leaves the payment alone until they do. */
    @Column(name = "review_required_at")
    private Instant reviewRequiredAt;

    @Column(name = "review_reason", length = 200)
    private String reviewReason;

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

    /**
     * Whether this payment funds its order: the provider holds or has taken the money
     * <em>and</em> the order was not cancelled. Money captured only to send it back
     * (D-109) is CAPTURED like any other, but it funds nothing: it is on its way to the
     * payer, and an order released or handed over against it is goods nobody paid for.
     * Every decision that lets an order proceed on a payment asks this, never
     * {@link PaymentStatus#fundsSecured()} alone.
     */
    public boolean fundsSecuredForOrder() {
        return status.fundsSecured() && cancelRequestedAt == null;
    }
}
