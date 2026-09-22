package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One day's reliability snapshot for one delivery provider. Doc 06 §4 (future).
 *
 * <p>Written by {@code DeliveryStatsAggregationService} nightly, read by
 * {@code DeliverySelection} when scoring providers. The table is append-only:
 * one row per {@code (provider_code, window_date)}, inserted the morning after
 * and never updated.
 *
 * <p>All counts are scoped to events where <em>this provider</em> was active —
 * a driver cancellation that triggered a waterfall to a second provider counts
 * against the first provider, not the second.
 */
@Entity
@Table(name = "delivery_provider_stats")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryProviderStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    /** The UTC calendar day whose completed deliveries are summarised here. */
    @Column(name = "window_date", nullable = false)
    private LocalDate windowDate;

    /** Deliveries reaching DRIVER_ASSIGNED on this day via this provider. */
    @Column(name = "total_bookings", nullable = false)
    private int totalBookings;

    /** Provider accepted but driver cancelled before pickup. */
    @Column(name = "driver_cancellations", nullable = false)
    private int driverCancellations;

    /** Driver arrived but could not collect. */
    @Column(name = "pickup_failures", nullable = false)
    private int pickupFailures;

    /** Goods collected but delivery ultimately failed. */
    @Column(name = "delivery_failures", nullable = false)
    private int deliveryFailures;

    /**
     * Deliveries delivered later than the quoted ETA from DRIVER_ASSIGNED.
     * Numerator for the SLA-breach rate.
     */
    @Column(name = "eta_overruns", nullable = false)
    private int etaOverruns;

    /** Deliveries that reached DELIVERED on this day (ETA overrun denominator). */
    @Column(name = "completed_deliveries", nullable = false)
    private int completedDeliveries;

    /** Average actual minutes from DRIVER_ASSIGNED → DELIVERED. Null if none. */
    @Column(name = "avg_actual_eta_minutes")
    private Integer avgActualEtaMinutes;

    /** Average quoted ETA minutes for bookings on this day. */
    @Column(name = "avg_quoted_eta_minutes")
    private Integer avgQuotedEtaMinutes;

    /**
     * Average price deviation: actual ledger cost minus quoted amount (INR).
     * Positive = provider charged more than quoted (surge). Null if no data.
     */
    @Column(name = "avg_price_deviation_inr", precision = 10, scale = 4)
    private BigDecimal avgPriceDeviationInr;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // -----------------------------------------------------------------------
    // Derived metrics — computed on demand, never persisted
    // -----------------------------------------------------------------------

    /**
     * Rate of driver cancellations out of total bookings.
     * Returns 0 when there are no bookings (avoids division by zero).
     */
    public double cancellationRate() {
        return totalBookings == 0 ? 0.0 : (double) driverCancellations / totalBookings;
    }

    /**
     * Rate of SLA/ETA breaches out of completed deliveries.
     * Returns 0 when there are no completions.
     */
    public double etaBreachRate() {
        return completedDeliveries == 0 ? 0.0 : (double) etaOverruns / completedDeliveries;
    }

    /**
     * Combined failure rate: driver cancellations + pickup failures + delivery
     * failures as a share of total bookings.
     */
    public double overallFailureRate() {
        if (totalBookings == 0) return 0.0;
        return (double) (driverCancellations + pickupFailures + deliveryFailures) / totalBookings;
    }
}
