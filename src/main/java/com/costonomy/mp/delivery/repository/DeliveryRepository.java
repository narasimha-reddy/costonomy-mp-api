package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findBySupplierOrderId(Long supplierOrderId);

    Optional<Delivery> findByProviderCodeAndProviderDeliveryId(
            String providerCode, String providerDeliveryId);

    List<Delivery> findByStatusIn(List<DeliveryStatus> statuses);

    List<Delivery> findByStatusAndAssignmentDeadlineBefore(
            DeliveryStatus status, java.time.Instant now);

    List<Delivery> findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            DeliveryStatus status, java.time.Instant now, Pageable page);

    List<Delivery> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            List<DeliveryStatus> statuses, java.time.Instant cutoff, Pageable page);

    /** Pidge deliveries of ours in these statuses created since the cutoff; the sandbox auto-advance job's sweep (D-196). */
    List<Delivery> findByProviderCodeAndModeAndProviderDeliveryIdIsNotNullAndStatusInAndCreatedAtAfter(
            String providerCode, com.costonomy.mp.delivery.domain.DeliveryMode mode,
            List<DeliveryStatus> statuses, java.time.Instant createdAfter);

    List<Delivery> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> lockById(@Param("id") Long id);

    @Query(value = """
            SELECT id FROM delivery
             WHERE mode = 'COSTONOMY'
               AND provider_delivery_id IS NOT NULL
               AND status IN ('PROVIDER_SELECTED', 'DRIVER_ASSIGNED', 'DRIVER_AT_PICKUP', 'PICKED_UP', 'IN_TRANSIT', 'ARRIVED_AT_DESTINATION')
               AND (poll_claimed_at IS NULL OR poll_claimed_at <= :staleThreshold)
             ORDER BY last_polled_at ASC, id DESC
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findDueDeliveryIdsForPolling(@Param("staleThreshold") java.time.Instant staleThreshold,
                                           @Param("batchSize") int batchSize);

    @Modifying
        @Transactional
    @Query(value = """
            UPDATE delivery
               SET poll_claimed_at = :now,
                                   poll_claim_token = :claimToken
             WHERE id = :id
                           AND mode = 'COSTONOMY'
                           AND provider_delivery_id IS NOT NULL
                           AND status IN ('PROVIDER_SELECTED', 'DRIVER_ASSIGNED', 'DRIVER_AT_PICKUP', 'PICKED_UP', 'IN_TRANSIT', 'ARRIVED_AT_DESTINATION')
               AND (poll_claimed_at IS NULL OR poll_claimed_at <= :staleThreshold)
            """, nativeQuery = true)
    int claimDeliveryForPoll(@Param("id") Long id,
                             @Param("now") java.time.Instant now,
                             @Param("staleThreshold") java.time.Instant staleThreshold,
                             @Param("claimToken") String claimToken);

    @Modifying
        @Transactional
    @Query(value = """
            UPDATE delivery
               SET last_polled_at = :now,
                   poll_claimed_at = NULL,
                                   poll_claim_token = NULL
             WHERE id = :id AND poll_claim_token = :claimToken
            """, nativeQuery = true)
    int releaseDeliveryAfterPoll(@Param("id") Long id,
                                 @Param("claimToken") String claimToken,
                                 @Param("now") java.time.Instant now);

    // The retry queries read the database's own clock and take durations in seconds, so no timestamp is bound from
    // Java. The clock is utc_timestamp(6), not now(6): hibernate.jdbc.time_zone is UTC, so no_partner_since and
    // last_retry_at are stored as UTC, while now(6) is in the database's zone. On a database that is not on UTC (a
    // laptop's MySQL, say) now(6) put every delivery outside its retry window and straight into the own-delivery
    // offer (D-185).

    /** Partner deliveries with no partner whose automatic retry is due. */
    @Query(value = """
            select id from delivery
             where mode = 'COSTONOMY' and status in ('QUOTE_FAILED', 'PROVIDER_UNAVAILABLE')
               and no_partner_since > utc_timestamp(6) - interval :windowSeconds second
               and (last_retry_at is null or last_retry_at <= utc_timestamp(6) - interval :intervalSeconds second)
               and auto_retry_count < :maxRetries
             order by last_retry_at is not null, last_retry_at
             limit :batch
            """, nativeQuery = true)
    List<Long> dueForRetry(@Param("windowSeconds") long windowSeconds,
                           @Param("intervalSeconds") long intervalSeconds,
                           @Param("maxRetries") int maxRetries, @Param("batch") int batch);

    /** Deliveries unserved long enough to offer the supplier delivering themselves. */
    @Query(value = """
            select id from delivery
             where mode = 'COSTONOMY' and status in ('QUOTE_FAILED', 'PROVIDER_UNAVAILABLE')
               and no_partner_since <= utc_timestamp(6) - interval :offerSeconds second and own_delivery_offered_at is null
             limit :batch
            """, nativeQuery = true)
    List<Long> dueForOffer(@Param("offerSeconds") long offerSeconds, @Param("batch") int batch);

    /** The claim: one conditional UPDATE, so two workers cannot both retry the same delivery. */
    @Modifying
    @Query(value = """
            update delivery set auto_retry_count = auto_retry_count + 1, last_retry_at = utc_timestamp(6)
             where id = :id and mode = 'COSTONOMY' and status in ('QUOTE_FAILED', 'PROVIDER_UNAVAILABLE')
               and no_partner_since > utc_timestamp(6) - interval :windowSeconds second
               and (last_retry_at is null or last_retry_at <= utc_timestamp(6) - interval :intervalSeconds second)
               and auto_retry_count < :maxRetries
            """, nativeQuery = true)
    int claimRetry(@Param("id") Long id, @Param("windowSeconds") long windowSeconds,
                   @Param("intervalSeconds") long intervalSeconds, @Param("maxRetries") int maxRetries);

    @Modifying
    @Query(value = """
            update delivery set own_delivery_offered_at = utc_timestamp(6)
             where id = :id and own_delivery_offered_at is null
               and status in ('QUOTE_FAILED', 'PROVIDER_UNAVAILABLE')
            """, nativeQuery = true)
    int claimOffer(@Param("id") Long id);
}
