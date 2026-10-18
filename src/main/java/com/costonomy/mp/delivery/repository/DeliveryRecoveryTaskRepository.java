package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryRecoveryTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryRecoveryTaskRepository extends JpaRepository<DeliveryRecoveryTask, Long> {

    Optional<DeliveryRecoveryTask> findByIdempotencyRef(String idempotencyRef);

    List<DeliveryRecoveryTask> findByStatusAndNextRetryAtBeforeOrderByNextRetryAtAsc(String status, Instant threshold);

    List<DeliveryRecoveryTask> findByFlowAndDeliveryIdOrderByCreatedAtAsc(String flow, Long deliveryId);

    List<DeliveryRecoveryTask> findByStatus(String status);

    long countByStatus(String status);

    long countByTaskTypeAndStatus(String taskType, String status);

    @Query(value = """
            SELECT id FROM delivery_recovery_task
             WHERE (status = 'PENDING' AND next_retry_at <= :now)
                                         OR (status = 'PROCESSING' AND (claimed_at IS NULL OR claimed_at <= :staleThreshold))
                                 ORDER BY (status = 'PROCESSING') ASC, next_retry_at ASC, id ASC
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findDueTaskIds(@Param("now") Instant now,
                              @Param("staleThreshold") Instant staleThreshold,
                              @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE delivery_recovery_task
               SET status = 'PROCESSING',
                   claimed_at = :now,
                   claim_token = :claimToken,
                   attempts = attempts + 1,
                   updated_at = :now,
                   version = version + 1
             WHERE id = :id
               AND ((status = 'PENDING' AND next_retry_at <= :now)
                    OR (status = 'PROCESSING' AND (claimed_at IS NULL OR claimed_at <= :staleThreshold)))
            """, nativeQuery = true)
    int claimTask(@Param("id") Long id,
                  @Param("now") Instant now,
                  @Param("staleThreshold") Instant staleThreshold,
                  @Param("claimToken") String claimToken);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE delivery_recovery_task
               SET status = 'COMPLETED',
                   claimed_at = NULL,
                   claim_token = NULL,
                   last_error = NULL,
                   updated_at = :now,
                   version = version + 1
             WHERE id = :id AND claim_token = :claimToken
            """, nativeQuery = true)
    int completeTask(@Param("id") Long id,
                     @Param("claimToken") String claimToken,
                     @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE delivery_recovery_task
               SET status = 'PENDING',
                   claimed_at = NULL,
                   claim_token = NULL,
                   next_retry_at = :nextRetryAt,
                   last_error = :lastError,
                   updated_at = :now,
                   version = version + 1
             WHERE id = :id AND claim_token = :claimToken
            """, nativeQuery = true)
    int retryTask(@Param("id") Long id,
                  @Param("claimToken") String claimToken,
                  @Param("nextRetryAt") Instant nextRetryAt,
                  @Param("lastError") String lastError,
                  @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE delivery_recovery_task
               SET status = 'EXHAUSTED',
                   claimed_at = NULL,
                   claim_token = NULL,
                   last_error = :lastError,
                   updated_at = :now,
                   version = version + 1
             WHERE id = :id AND claim_token = :claimToken
            """, nativeQuery = true)
    int exhaustTask(@Param("id") Long id,
                    @Param("claimToken") String claimToken,
                    @Param("lastError") String lastError,
                    @Param("now") Instant now);
}
