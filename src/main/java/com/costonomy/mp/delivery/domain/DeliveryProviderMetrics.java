package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Aggregated latency and cost metrics for a delivery provider over a 2-hour rolling window.
 *
 * <p>Written by {@code DeliveryMetricsAggregationService}, this table stores
 * performance profiles (average latency, p95 latency, order count, total cost)
 * for delivery operations and ops analytics.
 */
@Entity
@Table(name = "delivery_provider_metrics")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryProviderMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "avg_latency_ms", nullable = false)
    private double avgLatencyMs;

    @Column(name = "p95_latency_ms", nullable = false)
    private double p95LatencyMs;

    @Column(name = "total_cost_inr", nullable = false, precision = 12, scale = 4)
    private BigDecimal totalCostInr;

    @Column(name = "order_count", nullable = false)
    private long orderCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DeliveryProviderMetrics(String providerCode, Instant windowStart, double avgLatencyMs, double p95LatencyMs, BigDecimal totalCostInr, long orderCount) {
        this.providerCode = providerCode;
        this.windowStart = windowStart;
        this.avgLatencyMs = avgLatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.totalCostInr = totalCostInr;
        this.orderCount = orderCount;
    }
}
