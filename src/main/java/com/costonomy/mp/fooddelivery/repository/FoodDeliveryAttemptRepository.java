package com.costonomy.mp.fooddelivery.repository;

import com.costonomy.mp.fooddelivery.domain.FoodDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface FoodDeliveryAttemptRepository extends JpaRepository<FoodDeliveryAttempt, Long> {

    List<FoodDeliveryAttempt> findByFoodDeliveryIdOrderByAttemptNumberAsc(Long foodDeliveryId);

    Optional<FoodDeliveryAttempt> findFirstByFoodDeliveryIdOrderByAttemptNumberDesc(Long foodDeliveryId);

    List<FoodDeliveryAttempt> findByOutcomeAndStartedAtBefore(String outcome, java.time.Instant threshold);

    List<FoodDeliveryAttempt> findByOutcomeAndStartedAtBefore(String outcome, java.time.Instant threshold, org.springframework.data.domain.Pageable page);

    List<FoodDeliveryAttempt> findByOutcomeAndStartedAtBeforeOrderByStartedAtAscIdAsc(
            String outcome, java.time.Instant threshold, Pageable page);
}
