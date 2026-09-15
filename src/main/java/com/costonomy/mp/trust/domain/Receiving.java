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
 * What actually arrived. Doc 01 §22, doc 03 §11.
 *
 * <p>Recorded <em>alongside</em> the supplier order, never over it. The accepted
 * quantities are what the supplier committed to and what was paid for; a shortfall
 * at the door is a new fact, and erasing the commitment would destroy the evidence
 * a dispute is argued from.
 */
@Entity
@Table(name = "receiving")
@Getter
@Setter
@NoArgsConstructor
public class Receiving extends BaseEntity {

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private ReceivingStatus status = ReceivingStatus.PENDING;

    /** True when any line came up short, damaged or missing. */
    @Column(name = "has_discrepancy", nullable = false)
    private Boolean hasDiscrepancy = false;

    @Column(name = "total_accepted_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAcceptedQuantity = BigDecimal.ZERO;

    @Column(name = "total_received_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalReceivedQuantity = BigDecimal.ZERO;

    @Column(name = "total_damaged_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalDamagedQuantity = BigDecimal.ZERO;

    @Column(name = "total_missing_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalMissingQuantity = BigDecimal.ZERO;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "received_by")
    private Long receivedBy;

    @Column(name = "received_at")
    private Instant receivedAt;
}
