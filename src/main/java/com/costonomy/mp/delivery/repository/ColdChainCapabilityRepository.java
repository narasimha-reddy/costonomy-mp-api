package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.ColdChainCapability;
import com.costonomy.mp.delivery.domain.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ColdChainCapabilityRepository extends JpaRepository<ColdChainCapability, Long> {

    List<ColdChainCapability> findByDeliveryProviderId(Long deliveryProviderId);

    boolean existsByDeliveryProviderIdAndVehicleType(Long deliveryProviderId, VehicleType vehicleType);
}
