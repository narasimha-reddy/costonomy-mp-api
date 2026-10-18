package com.costonomy.mp.fooddelivery.repository;

import com.costonomy.mp.fooddelivery.domain.FoodDeliveryLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FoodDeliveryLocationRepository extends JpaRepository<FoodDeliveryLocation, Long> {

    /** The newest fix, which is the only one a tracking screen shows. */
    Optional<FoodDeliveryLocation> findFirstByFoodDeliveryIdOrderByRecordedAtDescIdDesc(Long foodDeliveryId);
}
