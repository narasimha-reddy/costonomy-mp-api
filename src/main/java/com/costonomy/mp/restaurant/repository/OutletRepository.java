package com.costonomy.mp.restaurant.repository;

import com.costonomy.mp.restaurant.domain.Outlet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutletRepository extends JpaRepository<Outlet, Long> {

    List<Outlet> findByRestaurantIdAndStatus(Long restaurantId, String status);

    List<Outlet> findByRestaurantId(Long restaurantId);
}
