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
 * One supplier store's credit line to one restaurant outlet. Doc 01 §18, v2.2 §14.
 *
 * <p>Store-and-outlet specific rather than organisation-wide: a supplier trading
 * from two cities carries different risk in each, and a restaurant group's outlets
 * are separate businesses to collect from.
 *
 * <p><b>{@code reservedAmount} and {@code utilizedAmount} are not written through
 * this entity.</b> They are mutated only by the conditional UPDATEs in
 * {@code CreditExposureStore}, which re-check the limit inside the same statement
 * — the read-modify-write this entity would otherwise invite is exactly the race
 * doc 10 §2 requires us to survive. The fields are mapped so they can be read.
 */
@Entity
@Table(name = "credit_agreement")
@Getter
@Setter
@NoArgsConstructor
public class CreditAgreement extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "supplier_organization_id", nullable = false)
    private Long supplierOrganizationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CreditAgreementStatus status = CreditAgreementStatus.REQUESTED;

    @Column(name = "approved_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal approvedLimit = BigDecimal.ZERO;

    @Column(name = "credit_period_days", nullable = false)
    private Integer creditPeriodDays = 0;

    @Column(name = "grace_period_days", nullable = false)
    private Integer gracePeriodDays = 0;

    /** A ceiling on any one order, independent of the limit (doc 01 §18). */
    @Column(name = "max_single_order_credit", precision = 19, scale = 4)
    private BigDecimal maxSingleOrderCredit;

    @Column(name = "max_overdue_amount", precision = 19, scale = 4)
    private BigDecimal maxOverdueAmount;

    @Column(name = "auto_suspend_enabled", nullable = false)
    private Boolean autoSuspendEnabled = true;

    /** Held against placed orders. Written only by {@code CreditExposureStore}. */
    @Column(name = "reserved_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedAmount = BigDecimal.ZERO;

    /** Drawn and not yet repaid. Written only by {@code CreditExposureStore}. */
    @Column(name = "utilized_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal utilizedAmount = BigDecimal.ZERO;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "review_date")
    private LocalDate reviewDate;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Bumped on every supplier modification. Doc 04 §13. */
    @Column(name = "terms_version", nullable = false)
    private Integer termsVersion = 1;

    /** Live availability, before invoice-derived dues are attached. */
    public BigDecimal available() {
        return approvedLimit.subtract(reservedAmount).subtract(utilizedAmount);
    }
}
