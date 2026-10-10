package com.costonomy.mp.common.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The publisher's batch: ids of pending events whose backoff has elapsed, oldest first. Ordering by id keeps
     * events for the same aggregate in the order they were produced. Ids only and no lock: each event is claimed
     * and handled in its own transaction ({@link #lockDispatchable}).
     */
    @Query("""
            select e.id from OutboxEvent e
            where e.status = com.costonomy.mp.common.outbox.OutboxEvent$Status.PENDING
              and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            order by e.id asc
            """)
    List<Long> findDispatchableIds(@Param("now") Instant now, Pageable pageable);

    /**
     * Claims one event for the caller's transaction: {@code FOR UPDATE SKIP LOCKED} on MySQL 8 (lock timeout -2).
     * Empty when another drain holds the row, or when it is no longer pending and due (published or failed since
     * the batch was listed). This, not the ShedLock, is what keeps an event from being handled twice if a drain
     * outlives its lock (D-194).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select e from OutboxEvent e
            where e.id = :id
              and e.status = com.costonomy.mp.common.outbox.OutboxEvent$Status.PENDING
              and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            """)
    Optional<OutboxEvent> lockDispatchable(@Param("id") Long id, @Param("now") Instant now);

    long countByStatus(OutboxEvent.Status status);
}
