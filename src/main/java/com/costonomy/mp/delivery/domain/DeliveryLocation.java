package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A driver position. Append-only. Doc 06 §8.
 *
 * <p><b>Never fabricated.</b> Every row came from a provider, and
 * {@code recordedAt} is their timestamp for the fix rather than ours. The
 * staleness indicator doc 06 §8 requires is the gap between that and now, which
 * only means something if we never write a row we did not receive — an
 * interpolated position is indistinguishable from a real one once stored.
 */
@Entity
@Table(name = "delivery_location")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "bearing", precision = 6, scale = 2)
    private BigDecimal bearing;

    @Column(name = "speed_kmph", precision = 6, scale = 2)
    private BigDecimal speedKmph;

    /** The provider's timestamp for the fix. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
