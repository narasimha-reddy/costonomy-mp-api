package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable retry queue for provider cancellations, wallet refunds, and reconciliation tasks (D-197).
 */
@Entity
@Table(name = "delivery_recovery_task")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryRecoveryTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 'SUPPLIER' or 'FOOD'. */
    @Column(name = "flow", nullable = false, length = 16)
    private String flow;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(name = "outlet_id")
    private Long outletId;

    /** 'PROVIDER_CANCEL', 'WALLET_REFUND', 'PROVIDER_RECONCILE'. */
    @Column(name = "task_type", nullable = false, length = 32)
    private String taskType;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    @Column(name = "provider_delivery_id", length = 200)
    private String providerDeliveryId;

    @Column(name = "idempotency_ref", length = 100)
    private String idempotencyRef;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", length = 500)
    private String reason;

    /** PENDING, PROCESSING, COMPLETED, EXHAUSTED, FAILED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt = Instant.now();

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
