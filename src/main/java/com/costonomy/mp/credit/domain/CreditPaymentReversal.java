package com.costonomy.mp.credit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A {@link CreditPayment} the supplier took back (B6, D-169). Append-only, one per payment: the payment row itself
 * never changes, so what happened can always be read from the two rows side by side.
 */
@Entity
@Table(name = "credit_payment_reversal")
@Getter
@Setter
@NoArgsConstructor
public class CreditPaymentReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "credit_payment_id", nullable = false)
    private Long creditPaymentId;

    /** The receipt it was reversed with; null for a payment recorded alone or a confirmed claim. */
    @Column(name = "receipt_id")
    private Long receiptId;

    @Column(name = "credit_invoice_id", nullable = false)
    private Long creditInvoiceId;

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "reversed_by")
    private Long reversedBy;

    @CreationTimestamp
    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;
}
