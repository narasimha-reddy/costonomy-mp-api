package com.costonomy.mp.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AuditRepository extends JpaRepository<AuditLog, Long> {

    /** "Everything that happened to this order." */
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, Long entityId, Pageable pageable);

    /** "Everything this operator did." Doc 09 §12 requires audit search by actor. */
    Page<AuditLog> findByActorIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long actorId, Instant from, Instant to, Pageable pageable);

    Page<AuditLog> findByActionAndCreatedAtBetweenOrderByCreatedAtDesc(
            String action, Instant from, Instant to, Pageable pageable);
}
