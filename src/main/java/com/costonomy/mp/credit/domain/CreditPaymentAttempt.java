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
 * One "Pay" a restaurant started on whole credit invoices, online (UPI, card) or from its wallet (D-196).
 *
 * <p>Written before Razorpay is asked, so a payment that arrives can always be matched to something (the wallet
 * top-up shape). <b>Its status is moved by the settlement under the row lock or by the repository's conditional
 * updates</b> ({@code attachOrder}, {@code endFromCreated}); nothing loads one, races, and saves it.
 */
@Entity
@Table(name = "credit_payment_attempt")
@Getter
@Setter
@NoArgsConstructor
public class CreditPaymentAttempt extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "method", nullable = false, length = 16)
    private CreditPayMethod method;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "channel", nullable = false, length = 16)
    private CreditPayChannel channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private CreditPaymentAttemptStatus status = CreditPaymentAttemptStatus.CREATED;

    @Column(name = "invoice_count", nullable = false)
    private int invoiceCount;

    /** The sum of the invoices' outstanding amounts at creation: what the suppliers are paid. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "fee_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal feePercent = BigDecimal.ZERO;

    /** Never paid out to a supplier. */
    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    /** amount + fee_amount: exactly what Razorpay is asked to collect (or the wallet debited). */
    @Column(name = "total_charged", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCharged;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "razorpay_order_id", length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    /** As Razorpay reports it (upi, card, netbanking...), or WALLET. */
    @Column(name = "payment_method", length = 32)
    private String paymentMethod;

    /** A card's last four digits or a provider wallet's name. Never a full card number or a UPI address (D-108). */
    @Column(name = "payment_detail", length = 64)
    private String paymentDetail;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** Set only while CREATED, so a second tap on the same selection reopens this attempt. */
    @Column(name = "open_selection_key", length = 120)
    private String openSelectionKey;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "stuck_alerted_at")
    private Instant stuckAlertedAt;
}
