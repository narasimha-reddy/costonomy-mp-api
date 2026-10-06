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
 * An amount taken off an invoice that is not a payment (B7, B8, D-151): a credit note for goods not supplied or an
 * agreed adjustment, or a write-off of what the supplier gave up on. Append-only: never edited, never deleted.
 *
 * <p>It never touches a payout and never earns commission: no money moves through Mandi.
 */
@Entity
@Table(name = "credit_note")
@Getter
@Setter
@NoArgsConstructor
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "credit_note_number", nullable = false, length = 40)
    private String creditNoteNumber;

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
    @Column(name = "reason_code", nullable = false, length = 32)
    private CreditNoteReason reasonCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 16)
    private CreditNoteKind kind;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "dispute_id")
    private Long disputeId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** Null for the system. */
    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
