package com.costonomy.mp.credit.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Every change to a limit or to terms. Append-only. Doc 01 §18: "supplier may
 * manually adjust exposure/limit with audit".
 *
 * <p>The reason is not nullable. An unexplained limit change is precisely what the
 * audit requirement exists to prevent, and a nullable column would make "no
 * reason given" a valid state rather than a bug.
 */
@Entity
@Table(name = "credit_limit_history")
@Getter
@Setter
@NoArgsConstructor
public class CreditLimitHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "terms_version", nullable = false)
    private Integer termsVersion;

    @Column(name = "previous_limit", precision = 19, scale = 4)
    private BigDecimal previousLimit;

    @Column(name = "new_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal newLimit;

    @Column(name = "previous_period_days")
    private Integer previousPeriodDays;

    @Column(name = "new_period_days", nullable = false)
    private Integer newPeriodDays;

    /** REQUEST, APPROVAL, MODIFICATION, MANUAL_ADJUSTMENT, AUTO_REDUCTION. */
    @Column(name = "change_type", nullable = false, length = 32)
    private String changeType;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "changed_by")
    private Long changedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
