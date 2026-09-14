package com.costonomy.mp.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One audited action. Doc 03 §17, doc 09 §7.
 *
 * <p>Does not extend {@code BaseEntity}: audit rows are append-only facts, not
 * aggregates. They are never updated, so {@code updated_at} and {@code version}
 * would be meaningless — and a {@code @Version} column on an audit table invites
 * the idea that a row could be rewritten, which is the one thing it must not be.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_role", length = 64)
    private String actorRole;

    /** What happened, e.g. {@code SUPPLIER_ORDER_ACCEPTED}. */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "old_state", length = 64)
    private String oldState;

    @Column(name = "new_state", length = 64)
    private String newState;

    /**
     * Fuller before/after snapshots, when the states alone do not explain the
     * change — a credit limit being modified, say.
     *
     * <p>Must never contain an OTP, token, password or provider secret
     * (doc 09 §16). {@link AuditService} redacts before writing.
     */
    @Column(name = "before_json", columnDefinition = "json")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "json")
    private String afterJson;

    /** Why. Required for suspensions, credit changes and operational overrides. */
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    /** Where it came from — {@code MOBILE}, {@code ADMIN_API}, {@code JOB}, {@code WEBHOOK}. */
    @Column(name = "source", length = 64)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
