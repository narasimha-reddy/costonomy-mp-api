package com.costonomy.mp.fooddelivery.repository;

import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FoodDeliveryRepository extends JpaRepository<FoodDelivery, Long> {

    /** Replay protection: the first delivery a restaurant made under this key. */
    Optional<FoodDelivery> findByRestaurantIdAndIdempotencyKey(Long restaurantId, String idempotencyKey);

    /** The public tracking link. The token, never the id, is what a customer holds. */
    Optional<FoodDelivery> findByTrackingToken(String trackingToken);

    /** A provider webhook or poll naming its own id for the consignment. */
    Optional<FoodDelivery> findByProviderCodeAndProviderDeliveryId(String providerCode, String providerDeliveryId);

    List<FoodDelivery> findByOutletIdOrderByCreatedAtDesc(Long outletId, Pageable page);

    List<FoodDelivery> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId, Pageable page);

    /** Booked but unassigned past the deadline: the waterfall's sweep. */
    List<FoodDelivery> findByStatusAndAssignmentDeadlineBefore(FoodDeliveryStatus status, Instant now);

    List<FoodDelivery> findByStatusAndAssignmentDeadlineBefore(FoodDeliveryStatus status, Instant now, Pageable page);

        List<FoodDelivery> findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            FoodDeliveryStatus status, Instant now, Pageable page);

    List<FoodDelivery> findByStatusIn(List<FoodDeliveryStatus> statuses);

    List<FoodDelivery> findByStatusInOrderByUpdatedAtAsc(List<FoodDeliveryStatus> statuses, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from FoodDelivery d where d.id = :id")
    Optional<FoodDelivery> lockById(@Param("id") Long id);

    @Query(value = """
            SELECT id FROM food_delivery
            WHERE status IN (:statuses)
              AND provider_code IS NOT NULL
              AND provider_delivery_id IS NOT NULL
              AND (last_polled_at IS NULL OR last_polled_at < :staleUpdateThreshold)
              AND (poll_claimed_at IS NULL OR poll_claimed_at < :staleClaimThreshold)
            ORDER BY id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findDueDeliveryIdsForPolling(
            @Param("statuses") java.util.Collection<String> statuses,
            @Param("staleUpdateThreshold") Instant staleUpdateThreshold,
            @Param("staleClaimThreshold") Instant staleClaimThreshold,
            @Param("limit") int limit);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
            UPDATE food_delivery
            SET poll_claimed_at = :claimedAt,
                poll_claim_token = :claimToken
            WHERE id = :id
              AND (poll_claimed_at IS NULL OR poll_claimed_at < :staleClaimThreshold)
            """, nativeQuery = true)
    int claimDeliveryForPoll(
            @Param("id") Long id,
            @Param("claimedAt") Instant claimedAt,
            @Param("staleClaimThreshold") Instant staleClaimThreshold,
            @Param("claimToken") String claimToken);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
            UPDATE food_delivery
            SET poll_claimed_at = NULL,
                poll_claim_token = NULL,
                last_polled_at = :polledAt
            WHERE id = :id
              AND poll_claim_token = :claimToken
            """, nativeQuery = true)
    int releaseDeliveryAfterPoll(
            @Param("id") Long id,
            @Param("claimToken") String claimToken,
            @Param("polledAt") Instant polledAt);
}
