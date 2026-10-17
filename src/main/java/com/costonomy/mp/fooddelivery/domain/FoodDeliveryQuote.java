package com.costonomy.mp.fooddelivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one provider said when asked. Append-only. <b>Internal</b>: no DTO and no endpoint returns it, because
 * the restaurant sees one fee and never the bidding (doc 06 §10). Declines and failures are recorded too, so a
 * delivery that fell back to the only courier left does not look like a choice.
 */
@Entity
@Table(name = "food_delivery_quote")
@Getter
@Setter
@NoArgsConstructor
public class FoodDeliveryQuote {

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

    /** QUOTED, UNSERVICEABLE or FAILED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency = "INR";

    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    @Column(name = "distance_km", precision = 9, scale = 4)
    private BigDecimal distanceKm;

    @Column(name = "provider_quote_id", length = 200)
    private String providerQuoteId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "selected", nullable = false)
    private boolean selected;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
