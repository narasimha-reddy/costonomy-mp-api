package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One go at getting this consignment carried. Append-only. Doc 06 §7.
 *
 * <p>What makes reassignment auditable rather than mysterious: attempt 1 to one
 * provider ending {@code CANCELLED}, attempt 2 to another ending {@code COMPLETED},
 * both on the same delivery. Reading the delivery alone would only ever show the
 * last courier and hide the fact that anything went wrong.
 */
@Entity
@Table(name = "delivery_provider_attempt")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryProviderAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(name = "delivery_provider_id")
    private Long deliveryProviderId;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

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
}
