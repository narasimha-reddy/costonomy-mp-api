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
 * What a Costonomy delivery will cost, quoted before the order exists. D-091.
 *
 * <p><b>Stored rather than recomputed.</b> The restaurant is shown this fee and
 * then charged it, and a fee recalculated between the screen and the charge is a
 * silent reprice — which §23A.16 forbids. Order creation references a quote by
 * id; an expired or mismatched one surfaces as a price change, shown old and
 * new, and confirmed.
 *
 * <p><b>Both endpoints are resolved by the server.</b> The caller names an outlet
 * and a store; the coordinates come from those rows. Accepting an origin from
 * the caller would let somebody quote a one-kilometre run and receive a
 * twenty-kilometre one, and guardrail 3 puts the arithmetic here regardless.
 *
 * <p><b>Half of this never leaves the server.</b> {@code vehicleType} and
 * {@code providerReference} are the platform's cost side. Doc 06 §10: bidding is
 * internal, the restaurant sees one fee and never a quote.
 */
@Entity
@Table(name = "delivery_fee_quote")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryFeeQuote extends BaseEntity {

    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    /** The request this was quoted for, so a quote cannot be reused elsewhere. */
    @Column(name = "intent_id")
    private Long intentId;

    @Column(name = "pickup_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLatitude;

    @Column(name = "pickup_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLongitude;

    @Column(name = "drop_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropLatitude;

    @Column(name = "drop_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropLongitude;

    @Column(name = "distance_km", nullable = false, precision = 9, scale = 4)
    private BigDecimal distanceKm;

    @Column(name = "weight_grams", nullable = false, precision = 19, scale = 4)
    private BigDecimal weightGrams;

    /** What the restaurant is shown, and what they are charged. */
    @Column(name = "fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal fee;

    // char(3) like every other currency column, which ddl-auto=validate
    // checks against rather than trusting the length alone.
    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    /**
     * Whether a provider produced this figure or the rate card did.
     *
     * <p>Recorded so a fee that was guessed is known to have been guessed. A
     * provider outage cannot block every order, so the quote degrades — but it
     * degrades visibly to operations, not silently.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 32)
    private DeliveryQuoteSource source = DeliveryQuoteSource.ESTIMATED;

    /** Internal. Drives booking and the platform's cost, never the price shown. */
    @Column(name = "vehicle_type", length = 32)
    private String vehicleType;

    @Column(name = "provider_reference", length = 200)
    private String providerReference;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Set when an order is created against this quote. One quote, one order. */
    @Column(name = "supplier_order_id")
    private Long supplierOrderId;

    /** Against a supplied clock, so the tests and the checkout agree on "now". */
    public boolean isExpiredAt(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
