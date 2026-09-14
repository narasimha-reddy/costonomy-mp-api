package com.costonomy.mp.common.outbox;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A domain event awaiting publication. Doc 02 §10, doc 08 §2–3.
 *
 * <p>Written in the same transaction as the state change that produced it, then
 * published asynchronously by {@link OutboxPublisher}. Delivery is at-least-once,
 * so every consumer must be idempotent — {@code eventId} is the deduplication key.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent extends BaseEntity {

    /**
     * Stable UUID. A consumer that has seen this id must ignore the event.
     *
     * <p>{@code CHAR(36)} rather than {@code VARCHAR(36)} — a UUID string is
     * always exactly 36 characters, and the column is uniquely indexed.
     */
    @Column(name = "event_id", nullable = false, columnDefinition = "char(36)")
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /** Bumped when the payload shape changes, so consumers can handle both. */
    @Column(name = "payload_version", nullable = false)
    private Integer payloadVersion = 1;

    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    // @JdbcTypeCode(VARCHAR) is required on every enum column in this codebase.
    // Hibernate 6.4 maps @Enumerated(STRING) to a *native MySQL ENUM*, which
    // ddl-auto=validate then rejects against our VARCHAR(32) columns. Beyond
    // making the build pass, VARCHAR is what we want: doc 02 §6 specifies it for
    // every status column, and a native ENUM would turn "add a delivery failure
    // state" into an ALTER TABLE on a large table.
    // (The global `hibernate.type.preferred_enum_jdbc_type` setting that would
    // replace this per-field annotation only exists from Hibernate 6.5.)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    /** When the publisher may next try. Null means immediately. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /**
     * When the business fact happened — not when the row was written.
     *
     * <p>These differ under retry, and consumers that order events or compute
     * durations need the business time, not the delivery time.
     */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public enum Status {
        PENDING,
        PUBLISHED,
        /**
         * Retries exhausted. Deliberately terminal and visible rather than
         * silently dropped: an unpublished {@code SupplierOrderAccepted} means a
         * restaurant was never notified, and someone needs to know.
         */
        FAILED,
    }
}
