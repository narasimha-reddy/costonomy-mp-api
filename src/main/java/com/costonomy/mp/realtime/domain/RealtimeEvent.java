package com.costonomy.mp.realtime.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One domain event, on one channel. Append-only.
 *
 * <p>The row every transport reads: the socket pushes it, the polling endpoint
 * walks it by cursor, and a reconnecting client resumes from the last id it saw.
 * One projection behind three transports is what makes them agree — a socket that
 * carried something polling could not would be a fact that exists only while you
 * are connected.
 */
@Entity
@Table(name = "realtime_event")
@Getter
@Setter
@NoArgsConstructor
public class RealtimeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The outbox event id. With {@code channel}, the deduplication key. */
    @Column(name = "event_id", nullable = false, columnDefinition = "char(36)")
    private String eventId;

    @Column(name = "channel", nullable = false, length = 100)
    private String channel;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
