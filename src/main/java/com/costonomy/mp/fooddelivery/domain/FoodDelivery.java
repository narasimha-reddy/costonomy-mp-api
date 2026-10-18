package com.costonomy.mp.fooddelivery.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import com.costonomy.mp.delivery.domain.VehicleType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Cooked food going from a restaurant outlet to that restaurant's customer. Doc 06 §16, D-201.
 *
 * <p>One row per request, for its whole life: a courier failing appends an attempt and an event
 * ({@link FoodDeliveryAttempt}, {@link FoodDeliveryEvent}), never a second row. Separate from
 * {@code delivery.domain.Delivery}, which is one per supplier order and is not touched.
 *
 * <p>The customer is only a drop-off contact. Mandi has no customer account, and no cash is collected.
 */
@Entity
@Table(name = "food_delivery")
@Getter
@Setter
@NoArgsConstructor
public class FoodDelivery extends BaseEntity {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    /** The restaurant's own order number, shown to the courier and the customer. */
    @Column(name = "external_order_ref", length = 100)
    private String externalOrderRef;

    /** Same key from the same restaurant returns the first delivery, never a second courier. */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 40)
    private FoodDeliveryStatus status = FoodDeliveryStatus.REQUESTED;

    @Column(name = "delivery_provider_id")
    private Long deliveryProviderId;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    @Column(name = "provider_delivery_id", length = 200)
    private String providerDeliveryId;

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

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 32)
    private String customerPhone;

    @Column(name = "drop_address", nullable = false, length = 500)
    private String dropAddress;

    /** The customer's locality, which couriers require to book (V94). Supplied by the request, never guessed. */
    @Column(name = "drop_city", length = 120)
    private String dropCity;

    @Column(name = "drop_state", length = 120)
    private String dropState;

    @Column(name = "drop_pincode", length = 16)
    private String dropPincode;

    @Column(name = "drop_landmark", length = 250)
    private String dropLandmark;

    @Column(name = "drop_latitude", precision = 10, scale = 7)
    private BigDecimal dropLatitude;

    @Column(name = "drop_longitude", precision = 10, scale = 7)
    private BigDecimal dropLongitude;

    @Column(name = "drop_instructions", length = 500)
    private String dropInstructions;

    /** The food bill, for the courier's declared value. Nothing is collected on delivery. */
    @Column(name = "order_value", precision = 19, scale = 4)
    private BigDecimal orderValue;

    @Column(name = "weight_kg", precision = 19, scale = 4)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "vehicle_type", length = 32)
    private VehicleType vehicleType;

    /** Ceiling on the courier's ETA for this food; a quote above it is not eligible. Null means no ceiling. */
    @Column(name = "max_delivery_minutes")
    private Integer maxDeliveryMinutes;

    /** What the restaurant pays. The selected quote; never a bid and never a provider list. */
    @Column(name = "fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal fee = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    /**
     * The most the restaurant agreed to pay, and what was held from its wallet at creation (V94). Net held is
     * {@code maxFee - feeRefundedAmount}; {@code fee} is what the booked courier will actually cost.
     */
    @Column(name = "max_fee", precision = 19, scale = 4)
    private BigDecimal maxFee;

    @Column(name = "fee_refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal feeRefundedAmount = BigDecimal.ZERO;

    /** When the current round of asking couriers began; "already tried" is counted from here. */
    @Column(name = "round_started_at")
    private Instant roundStartedAt;

    @Column(name = "fee_debit_ref", length = 100)
    private String feeDebitRef;

    @Column(name = "fee_debited_at")
    private Instant feeDebitedAt;

    @Column(name = "fee_refund_ref", length = 100)
    private String feeRefundRef;

    @Column(name = "fee_refunded_at")
    private Instant feeRefundedAt;

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

    @Column(name = "tracking_url", length = 1000)
    private String trackingUrl;

    /** The customer's public link: unguessable, not the row id. See {@link #newTrackingToken()}. */
    @Column(name = "tracking_token", nullable = false, length = 32, columnDefinition = "char(32)")
    private String trackingToken = newTrackingToken();

    @Column(name = "tracking_expires_at")
    private Instant trackingExpiresAt;

    /** Only the hash of the handover code is stored. */
    @Column(name = "delivery_otp_hash", length = 64, columnDefinition = "char(64)")
    private String deliveryOtpHash;

    @Column(name = "delivery_otp_attempts", nullable = false)
    private int deliveryOtpAttempts;

    /** Couriers asked so far. Incremented by reassignment, never reset. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** When an unassigned booking falls through to the next courier. */
    @Column(name = "assignment_deadline")
    private Instant assignmentDeadline;

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

    /** When the provider last told us anything; drives the staleness indicator. */
    @Column(name = "last_provider_update_at")
    private Instant lastProviderUpdateAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "poll_claimed_at")
    private Instant pollClaimedAt;

    @Column(name = "poll_claim_token", length = 36)
    private String pollClaimToken;

    /** Set when an ambiguous provider outcome or exhausted recovery requires operator intervention. */
    @Column(name = "reconciliation_required", nullable = false)
    private boolean reconciliationRequired;

    @Column(name = "reconciliation_reason", length = 255)
    private String reconciliationReason;

    /**
     * Moves to {@code target}, refusing a move the state machine does not allow. A refusal here is a programming
     * or ordering error, so it is an exception; a provider event that arrives out of order is filtered by the
     * caller with {@link FoodDeliveryStatus#rank()} before it gets this far.
     */
    public void moveTo(FoodDeliveryStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Food delivery " + getId() + " cannot go from " + status + " to " + target);
        }
        this.status = target;
    }

    /** What is still held from the restaurant's wallet: the agreed maximum less whatever has been given back. */
    public BigDecimal netHeld() {
        return maxFee == null ? BigDecimal.ZERO : maxFee.subtract(feeRefundedAmount);
    }

    /** 32 hex characters from 128 random bits: not derivable from the id and not guessable. */
    public static String newTrackingToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
