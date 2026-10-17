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

    List<FoodDelivery> findByStatusIn(List<FoodDeliveryStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from FoodDelivery d where d.id = :id")
    Optional<FoodDelivery> lockById(@Param("id") Long id);
}
