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

/** One invoice of a {@link CreditPaymentAttempt}, with the outstanding amount it was charged at (D-199). */
@Entity
@Table(name = "credit_payment_attempt_invoice")
@Getter
@Setter
@NoArgsConstructor
public class CreditPaymentAttemptInvoice extends BaseEntity {

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "credit_invoice_id", nullable = false)
    private Long creditInvoiceId;

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    /** What the invoice owed when the attempt was created; it must owe exactly this for the capture to pay it. */
    @Column(name = "expected_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "outcome", nullable = false, length = 16)
    private CreditPayInvoiceOutcome outcome = CreditPayInvoiceOutcome.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "skip_reason", length = 32)
    private CreditPaySkipReason skipReason;

    @Column(name = "credit_repayment_id")
    private Long creditRepaymentId;

    @Column(name = "credit_payment_id")
    private Long creditPaymentId;

    @Column(name = "credit_payment_refund_id")
    private Long creditPaymentRefundId;
}
