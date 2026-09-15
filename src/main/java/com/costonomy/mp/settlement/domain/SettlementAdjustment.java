package com.costonomy.mp.settlement.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A correction to a payout. Append-only. Doc 01 §17, doc 09 §11.
 *
 * <p>Direction is explicit rather than implied by the sign of the amount, so a
 * reader never has to infer whether a figure adds or subtracts from a minus sign
 * that may or may not be there — and the CHECK constraint can then require the
 * amount to be positive, which catches the bug where a debit is entered twice
 * negative and quietly becomes a credit.
 */
@Entity
@Table(name = "settlement_adjustment")
@Getter
@Setter
@NoArgsConstructor
public class SettlementAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    /** CREDIT adds to the payout; DEBIT subtracts. */
    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** REFUND, DISPUTE_RESOLUTION, MANUAL_CORRECTION, PENALTY, INCENTIVE. */
    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "supplier_order_id")
    private Long supplierOrderId;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The effect on the payout: positive for a credit, negative for a debit. */
    public BigDecimal signedAmount() {
        return "DEBIT".equals(direction) ? amount.negate() : amount;
    }
}
