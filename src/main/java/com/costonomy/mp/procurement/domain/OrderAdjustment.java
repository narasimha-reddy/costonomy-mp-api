package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One reduction of what an order comes to, after the goods left (D-129).
 *
 * <p>Immutable but for its status: the amount, reason and key are set at construction and never change, and a
 * row is never deleted. After ready, {@code supplier_order.final_payable_amount} is the accepted amount less
 * the sum of these.
 */
@Entity
@Table(name = "order_adjustment")
@Getter
@NoArgsConstructor
public class OrderAdjustment extends BaseEntity {

    @Column(name = "supplier_order_id", nullable = false, updatable = false)
    private Long supplierOrderId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason", nullable = false, length = 32, updatable = false)
    private OrderAdjustmentReason reason;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    /** The part a credit invoice could not come down by because it was already repaid (settled directly). */
    @Column(name = "settled_outside_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal settledOutsideAmount = BigDecimal.ZERO;

    @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
    private String idempotencyKey;

    @Column(name = "payment_method", nullable = false, length = 32, updatable = false)
    private String paymentMethod;

    @Column(name = "funding_reference", length = 120)
    private String fundingReference;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private OrderAdjustmentStatus status;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public static OrderAdjustment of(Long supplierOrderId, OrderAdjustmentReason reason, BigDecimal amount,
                                     String idempotencyKey, String paymentMethod, String note, Long createdBy) {
        var row = new OrderAdjustment();
        row.supplierOrderId = supplierOrderId;
        row.reason = reason;
        row.amount = amount;
        row.idempotencyKey = idempotencyKey;
        row.paymentMethod = paymentMethod;
        row.note = note == null ? null : (note.length() > 500 ? note.substring(0, 500) : note);
        row.createdBy = createdBy;
        return row;
    }

    /** The money moved: record where, and how much a credit invoice could not absorb. */
    public void markApplied(String fundingReference, BigDecimal settledOutside) {
        this.status = OrderAdjustmentStatus.APPLIED;
        this.fundingReference = fundingReference;
        this.settledOutsideAmount = settledOutside == null ? BigDecimal.ZERO : settledOutside;
        this.appliedAt = Instant.now();
    }

    /** Written, with the money still to move once the card payment is captured. */
    public void markPendingCapture() {
        this.status = OrderAdjustmentStatus.PENDING_CAPTURE;
    }
}
