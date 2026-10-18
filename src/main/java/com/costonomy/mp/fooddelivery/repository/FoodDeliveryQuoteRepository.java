package com.costonomy.mp.fooddelivery.repository;

import com.costonomy.mp.fooddelivery.domain.FoodDeliveryQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FoodDeliveryQuoteRepository extends JpaRepository<FoodDeliveryQuote, Long> {

    List<FoodDeliveryQuote> findByFoodDeliveryIdOrderByIdAsc(Long foodDeliveryId);
}
