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

/**
 * One movement of a restaurant's own money towards a credit agreement. D-121.
 *
 * <p>Credit is the supplier's: Mandi never funds or guarantees it, so a repayment is the restaurant paying
 * what it owes, recorded. One repayment can settle several invoices; the per-invoice rows are
 * {@link CreditPayment}s that point back here.
 */
@Entity
@Table(name = "credit_repayment")
@Getter
@Setter
@NoArgsConstructor
public class CreditRepayment extends BaseEntity {

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
    @Column(name = "source", nullable = false, length = 16)
    private CreditRepaymentSource source = CreditRepaymentSource.WALLET;

    /** The wallet debit that funded this repayment; null for UPI or card. */
    @Column(name = "wallet_transaction_id")
    private Long walletTransactionId;

    @Column(name = "provider_payment_id", length = 80)
    private String providerPaymentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private CreditRepaymentStatus status = CreditRepaymentStatus.COMPLETED;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "created_by")
    private Long createdBy;
}
