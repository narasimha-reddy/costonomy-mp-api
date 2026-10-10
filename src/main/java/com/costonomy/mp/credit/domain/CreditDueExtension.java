package com.costonomy.mp.credit.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One time a supplier gave an invoice longer to be paid (D-167). Append-only; the first row's {@code oldDueDate} is
 * the original due date, which the 60-day cap is measured from.
 */
@Entity
@Table(name = "credit_due_extension")
@Getter
@Setter
@NoArgsConstructor
public class CreditDueExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "credit_invoice_id", nullable = false)
    private Long creditInvoiceId;

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "old_due_date", nullable = false)
    private LocalDate oldDueDate;

    @Column(name = "new_due_date", nullable = false)
    private LocalDate newDueDate;

    @Column(name = "old_overdue_after", nullable = false)
    private LocalDate oldOverdueAfter;

    @Column(name = "new_overdue_after", nullable = false)
    private LocalDate newOverdueAfter;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "extended_by")
    private Long extendedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
