package com.costonomy.mp.credit.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement in the credit ledger. Append-only, so no {@code BaseEntity}.
 *
 * <p>Carries the balances <em>after</em> it was applied. That makes the ledger
 * readable forwards without re-deriving arithmetic, and it means a disagreement
 * about today's exposure can be traced to the movement that caused it rather than
 * argued about.
 */
@Entity
@Table(name = "credit_transaction")
@Getter
@Setter
@NoArgsConstructor
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "credit_reservation_id")
    private Long creditReservationId;

    @Column(name = "supplier_order_id")
    private Long supplierOrderId;

    @Column(name = "credit_invoice_id")
    private Long creditInvoiceId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private CreditTransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_reserved_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceReservedAfter;

    @Column(name = "balance_utilized_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceUtilizedAfter;

    @Column(name = "balance_available_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAvailableAfter;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
