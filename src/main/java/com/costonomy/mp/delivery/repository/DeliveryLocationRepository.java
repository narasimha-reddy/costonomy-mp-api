package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeliveryLocationRepository extends JpaRepository<DeliveryLocation, Long> {

    /** The newest fix, which is the only one a tracking screen shows. */
    Optional<DeliveryLocation> findFirstByDeliveryIdOrderByRecordedAtDescIdDesc(Long deliveryId);
}
