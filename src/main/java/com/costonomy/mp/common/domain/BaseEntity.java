package com.costonomy.mp.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Audit columns and optimistic locking for every aggregate.
 *
 * <p>Doc 02 §6 requires {@code status}, {@code version}, {@code created_at} and
 * {@code updated_at} on every stateful aggregate. {@code status} varies by type
 * so subclasses declare it; the rest live here.
 *
 * <p>{@link Version} is what makes the concurrency requirements in doc 10 §2
 * enforceable. Acceptance racing a timeout, two duplicate acceptances, two
 * credit reservations — in each case both transactions read the same version and
 * only one write succeeds; the loser gets an
 * {@code OptimisticLockingFailureException}, mapped to
 * {@code CONCURRENT_MODIFICATION}. Removing {@code @Version} from an aggregate
 * silently removes that guarantee.
 *
 * <p>Timestamps are {@link Instant} — an absolute point in time, no timezone.
 * Doc 02 §1 stores UTC and converts for presentation at the edge. A
 * {@code LocalDateTime} here would make "which zone is this?" a per-call-site
 * question, and the supplier response deadline is not a field to get that wrong on.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Identity is database identity. Two unsaved entities are never equal. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity that)) {
            return false;
        }
        // Hibernate proxies make getClass() comparison unreliable, and a null id
        // means "not persisted yet", which is never equal to anything else.
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        // Constant, deliberately: a hash derived from a generated id changes
        // when the entity is persisted, which corrupts any HashSet it is already
        // in. Constant hashing is the standard JPA answer.
        return getClass().hashCode();
    }
}
