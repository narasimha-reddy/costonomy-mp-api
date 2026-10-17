package com.costonomy.mp.fooddelivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** A rider position. Append-only. Never fabricated: every row came from a provider (doc 06 §8). */
@Entity
@Table(name = "food_delivery_location")
@Getter
@Setter
@NoArgsConstructor
public class FoodDeliveryLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "food_delivery_id", nullable = false)
    private Long foodDeliveryId;

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
