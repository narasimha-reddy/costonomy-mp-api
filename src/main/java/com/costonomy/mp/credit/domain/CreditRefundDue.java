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
 * Money a restaurant had already paid on an invoice whose order was then cancelled (B7, D-177). A credit note cannot
 * cancel paid money, so it is owed back: the supplier refunds off-platform and marks it REFUNDED, or, when the
 * restaurant paid from its Mandi wallet (channel WALLET), ops puts it right.
 */
@Entity
@Table(name = "credit_refund_due")
@Getter
@Setter
@NoArgsConstructor
public class CreditRefundDue extends BaseEntity {

    public enum Channel { OFF_PLATFORM, WALLET }

    public enum Status { OPEN, REFUNDED }

    @Column(name = "credit_note_id")
    private Long creditNoteId;

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
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "refunded_by")
    private Long refundedBy;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;
}
