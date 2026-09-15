package com.costonomy.mp.settlement.domain;

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
 * What is paid to one supplier store for one period. Doc 01 §17, doc 03 §13.
 *
 * <p>All four figures of {@code Gross - Commission ± Adjustments = Net} are
 * stored. The supplier's statement shows every line of that equation (doc 05 §33),
 * and deriving three of them from one at read time would let a rounding difference
 * appear between what we paid and what we said we paid.
 */
@Entity
@Table(name = "settlement")
@Getter
@Setter
@NoArgsConstructor
public class Settlement extends BaseEntity {

    @Column(name = "settlement_number", nullable = false, length = 40)
    private String settlementNumber;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "supplier_organization_id", nullable = false)
    private Long supplierOrganizationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private SettlementStatus status = SettlementStatus.PENDING;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    /** Period end plus the configured offset — T+2 by default (doc 01 §17). */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "commission_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    /** Signed: credits add, debits subtract. */
    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal adjustmentAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount = 0;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    /** What the payment records actually say was captured for these orders. */
    @Column(name = "reconciled_gross", precision = 19, scale = 4)
    private BigDecimal reconciledGross;

    @Column(name = "reconciliation_note", length = 500)
    private String reconciliationNote;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_reference", length = 200)
    private String paymentReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;
}
