package com.costonomy.mp.fooddelivery.repository;

import com.costonomy.mp.fooddelivery.domain.FoodDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FoodDeliveryAttemptRepository extends JpaRepository<FoodDeliveryAttempt, Long> {

    List<FoodDeliveryAttempt> findByFoodDeliveryIdOrderByAttemptNumberAsc(Long foodDeliveryId);

    Optional<FoodDeliveryAttempt> findFirstByFoodDeliveryIdOrderByAttemptNumberDesc(Long foodDeliveryId);

    List<FoodDeliveryAttempt> findByOutcomeAndStartedAtBefore(String outcome, java.time.Instant threshold);
}
