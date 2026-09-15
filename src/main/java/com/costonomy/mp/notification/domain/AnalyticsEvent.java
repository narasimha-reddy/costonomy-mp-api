package com.costonomy.mp.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One product-analytics event. Append-only. Doc 08 §7.
 *
 * <p>Carries internal ids only — which outlet, not which person (doc 08 §8) — and
 * never a secret. The stripping happens at ingest rather than here, because by the
 * time a row exists it is too late.
 */
@Entity
@Table(name = "analytics_event")
@Getter
@Setter
@NoArgsConstructor
public class AnalyticsEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The client's id for this occurrence, so a retried batch does not double-count. */
    @Column(name = "client_event_id", length = 100)
    private String clientEventId;

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "outlet_id")
    private Long outletId;

    @Column(name = "supplier_store_id")
    private Long supplierStoreId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "platform", length = 16)
    private String platform;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "properties", columnDefinition = "json")
    private String properties;

    /** Device time. The app batches, so this can be well before we saw it. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
