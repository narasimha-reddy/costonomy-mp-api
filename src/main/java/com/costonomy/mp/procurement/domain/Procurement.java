package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * One attempt to source a requirement. Also the cart. Doc 02 §4, doc 03 §4.
 *
 * <p>Cart and procurement are the same object at different points in its life,
 * which is why a cart survives an app restart, is visible to an approver, and
 * becomes an order without being copied anywhere.
 *
 * <p>Every money field here is computed by the server and recomputed on every
 * validation. Guardrail 3: a client-supplied total is not merely distrusted, it
 * has nowhere to go.
 */
@Entity
@Table(name = "procurement")
@Getter
@Setter
@NoArgsConstructor
public class Procurement extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    /** Null for a cart built by browsing rather than from a requirement. */
    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private ProcurementStatus status = ProcurementStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "approval_status", nullable = false, length = 32)
    private ApprovalStatus approvalStatus = ApprovalStatus.NOT_REQUIRED;

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod = "PREPAID";

    @Column(name = "payment_status", nullable = false, length = 32)
    private String paymentStatus = "NOT_STARTED";

    @Column(name = "total_item_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalItemValue = BigDecimal.ZERO;

    @Column(name = "total_gst", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalGst = BigDecimal.ZERO;

    @Column(name = "total_delivery_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalDeliveryFee = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** The policy that held this order, and the version of it that applied. */
    @Column(name = "approval_policy_id")
    private Long approvalPolicyId;

    @Column(name = "approval_policy_version")
    private Integer approvalPolicyVersion;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** When prices were last confirmed against live offers. */
    @Column(name = "validated_at")
    private Instant validatedAt;

    /**
     * Whether the validation is old enough that prices must be re-checked.
     *
     * <p>Doc 01 §11 requires revalidation at checkout, and §23A.16 requires a
     * changed price to be surfaced rather than absorbed. A validation is a claim
     * about live offers at a moment, and offers move — so the claim expires.
     */
    public boolean isValidationStale(Duration maxAge) {
        return validatedAt == null || validatedAt.plus(maxAge).isBefore(Instant.now());
    }

    /** Whether the approval policy is satisfied, if one applied at all. */
    public boolean isApprovalSatisfied() {
        return approvalStatus == ApprovalStatus.NOT_REQUIRED
                || approvalStatus == ApprovalStatus.APPROVED;
    }
}
