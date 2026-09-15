package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one provider said when asked. Append-only.
 *
 * <p><b>Internal.</b> Doc 06 §4 and §10: the restaurant sees one fee and never the
 * bidding. There is no DTO for this type and no endpoint returns one; that is a
 * deliberate absence, not an oversight.
 *
 * <p>Declines and failures are recorded alongside successes. Without them, a
 * delivery that fell back to the only provider left looks like a choice.
 */
@Entity
@Table(name = "delivery_quote")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryQuote {

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
    private Boolean selected = false;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
