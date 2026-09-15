package com.costonomy.mp.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * The timeline. Append-only. Doc 06 §9, §12.
 *
 * <p>Every event is stored, including the ones that changed nothing —
 * {@code disposition} says which. A duplicate and an out-of-order event both
 * arrive routinely from a real provider (doc 06 §12), and discarding them would
 * leave nobody able to explain why a status did not move when the provider
 * insists they sent it.
 */
@Entity
@Table(name = "delivery_event")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 40)
    private DeliveryStatus status;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    /** The provider's id for this event. The deduplication key. */
    @Column(name = "provider_event_id", length = 200)
    private String providerEventId;

    @Column(name = "provider_status", length = 64)
    private String providerStatus;

    /** APPLIED, DUPLICATE, OUT_OF_ORDER or IGNORED. */
    @Column(name = "disposition", nullable = false, length = 32)
    private String disposition = "APPLIED";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "payload", columnDefinition = "json")
    private String payload;

    /** The provider's timestamp, which may be well before we saw it. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
