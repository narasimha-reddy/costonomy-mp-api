package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One product a requirement needs.
 *
 * <p>{@code requestedQuantity} is fixed once set; {@code fulfilledQuantity} only
 * grows. The remainder is {@link #remainingQuantity()} — derived, never stored, so
 * the two cannot drift apart.
 */
@Entity
@Table(name = "requirement_item")
@Getter
@Setter
@NoArgsConstructor
public class RequirementItem extends BaseEntity {

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "canonical_product_id", nullable = false)
    private Long canonicalProductId;

    @Column(name = "requested_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedQuantity;

    @Column(name = "fulfilled_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal fulfilledQuantity = BigDecimal.ZERO;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "notes", length = 500)
    private String notes;

    /** OPEN, PARTIALLY_FULFILLED, FULFILLED or CANCELLED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "OPEN";

    /**
     * What is still needed.
     *
     * <p>The number §23A.14 requires to be explicit and guardrail 14 requires to
     * survive a supplier failing. Never negative — the database check constraint
     * guarantees fulfilled cannot exceed requested.
     */
    public BigDecimal remainingQuantity() {
        BigDecimal remaining = requestedQuantity.subtract(fulfilledQuantity);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    public boolean isFullyFulfilled() {
        return remainingQuantity().signum() == 0;
    }
}
