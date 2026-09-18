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

/** Credit held against one supplier order. Doc 01 §19, doc 03 §9. */
@Entity
@Table(name = "credit_reservation")
@Getter
@Setter
@NoArgsConstructor
public class CreditReservation extends BaseEntity {

    @Column(name = "credit_agreement_id", nullable = false)
    private Long creditAgreementId;

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    /**
     * The checkout this reservation belonged to, or null when the order came from
     * an intent.
     *
     * <p>Nullable since V25, for the same reason as {@code Payment}: credit is
     * reserved per supplier order, and an intent-built order has no cart behind
     * it.
     */
    @Column(name = "procurement_id")
    private Long procurementId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CreditReservationStatus status = CreditReservationStatus.REQUESTED;

    /** The full order value — what will be accepted is not yet known. */
    @Column(name = "reserved_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedAmount = BigDecimal.ZERO;

    /** The accepted value. Zero after a rejection (doc 01 §19). */
    @Column(name = "utilized_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal utilizedAmount = BigDecimal.ZERO;

    @Column(name = "released_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal releasedAmount = BigDecimal.ZERO;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "reserved_at")
    private Instant reservedAt;

    @Column(name = "utilized_at")
    private Instant utilizedAt;

    @Column(name = "released_at")
    private Instant releasedAt;
}
