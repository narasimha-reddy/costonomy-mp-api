package com.costonomy.mp.trust.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A complaint about an order. Doc 01 §23, doc 03 §12.
 *
 * <p>Several are possible per order, deliberately: a delivery can be both short
 * and damaged, and one dispute per order would force a restaurant to pick which
 * problem to report.
 */
@Entity
@Table(name = "dispute")
@Getter
@Setter
@NoArgsConstructor
public class Dispute extends BaseEntity {

    @Column(name = "dispute_number", nullable = false, length = 40)
    private String disputeNumber;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "receiving_id")
    private Long receivingId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 32)
    private DisputeCategory category;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private DisputeStatus status = DisputeStatus.OPEN;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    /** The restaurant's claim. Recorded, not adjudicated — Mandi is not the judge. */
    @Column(name = "claimed_amount", precision = 19, scale = 4)
    private BigDecimal claimedAmount;

    @Column(name = "resolution", length = 2000)
    private String resolution;

    /** REPLACEMENT, REFUND, CREDIT_NOTE or NO_ACTION. What was agreed. */
    @Column(name = "resolution_type", length = 32)
    private String resolutionType;

    @Column(name = "raised_by", nullable = false)
    private Long raisedBy;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
