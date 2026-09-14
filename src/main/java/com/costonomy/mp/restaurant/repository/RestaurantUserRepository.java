package com.costonomy.mp.restaurant.repository;

import com.costonomy.mp.restaurant.domain.RestaurantUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RestaurantUserRepository extends JpaRepository<RestaurantUser, Long> {

    Optional<RestaurantUser> findByRestaurantIdAndUserId(Long restaurantId, Long userId);

    List<RestaurantUser> findByRestaurantIdAndStatus(Long restaurantId, String status);

    List<RestaurantUser> findByUserIdAndStatus(Long userId, String status);
}
