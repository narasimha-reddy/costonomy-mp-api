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
import java.time.LocalDate;

/**
 * A restaurant's statement that it paid a supplier directly (D-125).
 *
 * <p>A claim is not a payment. It changes nothing about the invoice, the exposure or the credit line until the
 * supplier confirms it, and the confirmation writes the {@link CreditPayment}. Credit is the supplier's: the party
 * the money reached is the only one who can say it arrived.
 */
@Entity
@Table(name = "credit_payment_claim")
@Getter
@Setter
@NoArgsConstructor
public class CreditPaymentClaim extends BaseEntity {

    @Column(name = "credit_invoice_id", nullable = false)
    private Long creditInvoiceId;

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "method", nullable = false, length = 32)
    private CreditPaymentMethod method;

    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "note", length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private CreditClaimStatus status = CreditClaimStatus.SUBMITTED;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "confirmed_amount", precision = 19, scale = 4)
    private BigDecimal confirmedAmount;

    @Column(name = "credit_payment_id")
    private Long creditPaymentId;

    @Column(name = "claimed_by")
    private Long claimedBy;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;
}
