package com.costonomy.mp.delivery.domain;

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
 * One consignment. Doc 06 §5.
 *
 * <p>One per supplier order, for the life of that order — a driver cancelling or a
 * provider failing appends an attempt and an event, never a second delivery row
 * (doc 06 §7). {@code attemptCount} is how many couriers have been asked.
 */
@Entity
@Table(name = "delivery")
@Getter
@Setter
@NoArgsConstructor
public class Delivery extends BaseEntity {

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "mode", nullable = false, length = 32)
    private DeliveryMode mode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 40)
    private DeliveryStatus status = DeliveryStatus.DELIVERY_REQUESTED;

    @Column(name = "delivery_provider_id")
    private Long deliveryProviderId;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    @Column(name = "provider_delivery_id", length = 200)
    private String providerDeliveryId;

    /** What the restaurant pays. Never a bid (doc 06 §10). */
    @Column(name = "fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal fee = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "pickup_address", nullable = false, length = 500)
    private String pickupAddress;

    @Column(name = "pickup_latitude", precision = 10, scale = 7)
    private BigDecimal pickupLatitude;

    @Column(name = "pickup_longitude", precision = 10, scale = 7)
    private BigDecimal pickupLongitude;

    @Column(name = "pickup_contact_name", length = 150)
    private String pickupContactName;

    @Column(name = "pickup_contact_phone", length = 32)
    private String pickupContactPhone;

    @Column(name = "drop_address", nullable = false, length = 500)
    private String dropAddress;

    @Column(name = "drop_latitude", precision = 10, scale = 7)
    private BigDecimal dropLatitude;

    @Column(name = "drop_longitude", precision = 10, scale = 7)
    private BigDecimal dropLongitude;

    @Column(name = "drop_contact_name", length = 150)
    private String dropContactName;

    @Column(name = "drop_contact_phone", length = 32)
    private String dropContactPhone;

    /** Null until a driver exists. Never filled with a placeholder. */
    @Column(name = "driver_name", length = 150)
    private String driverName;

    @Column(name = "driver_phone", length = 32)
    private String driverPhone;

    @Column(name = "driver_vehicle", length = 100)
    private String driverVehicle;

    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    @Column(name = "estimated_arrival_at")
    private Instant estimatedArrivalAt;

    /** Couriers asked so far. Incremented by reassignment, never reset. */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "booked_at")
    private Instant bookedAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** When the provider last told us anything. Drives the staleness indicator. */
    @Column(name = "last_provider_update_at")
    private Instant lastProviderUpdateAt;
}
