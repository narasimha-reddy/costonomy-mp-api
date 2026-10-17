package com.costonomy.mp.fooddelivery.repository;

import com.costonomy.mp.fooddelivery.domain.FoodDeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FoodDeliveryEventRepository extends JpaRepository<FoodDeliveryEvent, Long> {

    List<FoodDeliveryEvent> findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(Long foodDeliveryId);

    /** Whether this provider event was already seen: the deduplication check. */
    boolean existsByProviderCodeAndProviderEventId(String providerCode, String providerEventId);
}
