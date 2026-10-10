package com.costonomy.mp.credit.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/** One invoice a reminder is about. Append-only; {@code autoKey} makes an automatic reminder once per invoice, kind and day. */
@Entity
@Table(name = "credit_reminder_invoice")
@Getter
@Setter
@NoArgsConstructor
public class CreditReminderInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credit_reminder_id", nullable = false)
    private Long creditReminderId;

    @Column(name = "credit_invoice_id", nullable = false)
    private Long creditInvoiceId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 16)
    private CreditReminderKind kind;

    /** The India day the reminder was made. */
    @Column(name = "remind_date", nullable = false)
    private LocalDate remindDate;

    /** {@code KIND:yyyy-MM-dd} for an automatic reminder, null for a manual one. */
    @Column(name = "auto_key", length = 40)
    private String autoKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
