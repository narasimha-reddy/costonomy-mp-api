package com.costonomy.mp.credit.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What one drawn-down order owes. Doc 01 §18, doc 10 §1 scenario 7.
 *
 * <p>Raised when credit is <em>utilized</em>, never when the order is placed:
 * until a supplier accepts, nothing has been supplied and nothing is owed.
 *
 * <p>{@code dueDate} and {@code overdueAfter} are snapshotted rather than derived
 * from the agreement at read time. Deriving them would silently re-date every
 * existing invoice the moment a supplier changed their terms — turning a
 * forward-looking change into a retrospective one, against debts already incurred.
 */
@Entity
@Table(name = "credit_invoice")
@Getter
@Setter
@NoArgsConstructor
public class CreditInvoice extends BaseEntity {

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "invoice_number", nullable = false, length = 40)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CreditInvoiceStatus status = CreditInvoiceStatus.ISSUED;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** Due date plus the grace period. Overdue begins here. */
    @Column(name = "overdue_after", nullable = false)
    private LocalDate overdueAfter;

    @Column(name = "marked_overdue_at")
    private Instant markedOverdueAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    public BigDecimal outstanding() {
        return amount.subtract(paidAmount);
    }
}
