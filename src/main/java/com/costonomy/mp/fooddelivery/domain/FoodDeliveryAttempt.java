package com.costonomy.mp.fooddelivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One go at getting this food carried. Append-only: the waterfall's record, e.g. attempt 1 to one provider
 * ending in a timeout, attempt 2 to another ending {@code COMPLETED}, on the same delivery.
 */
@Entity
@Table(name = "food_delivery_attempt")
@Getter
@Setter
@NoArgsConstructor
public class FoodDeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "food_delivery_id", nullable = false)
    private Long foodDeliveryId;

    @Column(name = "delivery_provider_id")
    private Long deliveryProviderId;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    /** BOOKING or REASSIGNMENT. */
    @Column(name = "attempt_type", nullable = false, length = 32)
    private String attemptType = "BOOKING";

    /** BOOKED, FAILED, CANCELLED or COMPLETED. */
    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "provider_delivery_id", length = 200)
    private String providerDeliveryId;

    @Column(name = "quoted_amount", precision = 19, scale = 4)
    private BigDecimal quotedAmount;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Why this attempt ended, as a code (V92, D-201 addendum). Null on rows from before, and while it is live. */
    @Column(name = "ended_cause", length = 40)
    private String endedCause;

    @Column(name = "retryable")
    private Boolean retryable;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** How long the provider's API took to answer the booking. */
    @Column(name = "call_ms")
    private Integer callMs;

    /** 1 = first choice among the couriers considered for this attempt. */
    @Column(name = "rank_position")
    private Integer rankPosition;

    @Column(name = "selection_reason", length = 32)
    private String selectionReason;

    @Column(name = "reliability_score", precision = 5, scale = 4)
    private java.math.BigDecimal reliabilityScore;

    @Column(name = "breaker_state", length = 16)
    private String breakerState;

    /** Every courier considered for this attempt and why each was ranked or skipped. JSON. */
    @Column(name = "decision", columnDefinition = "json")
    private String decision;
}
