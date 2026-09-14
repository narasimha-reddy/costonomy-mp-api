package com.costonomy.mp.common.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The publisher's batch: pending events whose backoff has elapsed, oldest
     * first. Ordering by id keeps events for the same aggregate in the order they
     * were produced.
     */
    @Query("""
            select e from OutboxEvent e
            where e.status = com.costonomy.mp.common.outbox.OutboxEvent$Status.PENDING
              and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            order by e.id asc
            """)
    List<OutboxEvent> findDispatchable(@Param("now") Instant now, Pageable pageable);

    long countByStatus(OutboxEvent.Status status);
}
