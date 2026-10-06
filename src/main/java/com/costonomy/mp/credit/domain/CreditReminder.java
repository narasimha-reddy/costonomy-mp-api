package com.costonomy.mp.credit.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A reminder to a restaurant about what it owes on a credit line (D-142). Nothing about the money changes: it is a
 * message, and the row is the proof of who sent what, when, and how often, which is what the limits are measured on.
 */
@Entity
@Table(name = "credit_reminder")
@Getter
@Setter
@NoArgsConstructor
public class CreditReminder extends BaseEntity {

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 16)
    private CreditReminderKind kind;

    /** Comma-separated channels the reminder is sent on: IN_APP, PUSH, SMS. */
    @Column(name = "channel", nullable = false, length = 64)
    private String channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private CreditReminderStatus status;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    /** The supplier user; null for an automatic reminder. */
    @Column(name = "created_by")
    private Long createdBy;

    /** The credit clock's instant when it was asked for (or generated). The limits count from here. */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "idempotency_key", length = 160)
    private String idempotencyKey;
}
