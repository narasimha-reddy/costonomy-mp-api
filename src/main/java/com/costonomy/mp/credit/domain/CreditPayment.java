package com.costonomy.mp.credit.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A repayment recorded against an invoice. Append-only.
 *
 * <p><b>Recorded, not collected.</b> The money moves directly between restaurant
 * and supplier; Mandi reconciles it. Doc 01 §18 is explicit that Mandi does not
 * fund credit, own receivables or perform recovery — so there is no provider
 * reference here and nothing is captured.
 */
@Entity
@Table(name = "credit_payment")
@Getter
@Setter
@NoArgsConstructor
public class CreditPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "credit_invoice_id", nullable = false)
    private Long creditInvoiceId;

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** BANK_TRANSFER, UPI, CASH, CHEQUE, ADJUSTMENT. */
    @Column(name = "method", nullable = false, length = 32)
    private String method;

    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(name = "recorded_by")
    private Long recordedBy;

    /** Doc 04 §21: a repeated recording must not reduce the debt twice. */
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
