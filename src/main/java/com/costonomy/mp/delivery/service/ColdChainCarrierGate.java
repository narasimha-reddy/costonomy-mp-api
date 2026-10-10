package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.repository.ColdChainCapabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Whether a carrier may carry chilled goods, from recorded capability and nothing else (D-134, D-121).
 *
 * <p>"A three-wheeler is insulated" is an assumption, and a carrier echoing back the vehicle we asked for is not
 * evidence either. A chilled consignment qualifies for a carrier only if that carrier has a capability row for the
 * vehicle class, and a quote qualifies only if the carrier states a vehicle and that class is in the table. With no
 * qualifying carrier the answer is "none", never a guess.
 */
@Component
@RequiredArgsConstructor
public class ColdChainCarrierGate {

    private final ColdChainCapabilityRepository capabilities;

    /** Whether this carrier is verified to carry chilled goods in this vehicle class. */
    public boolean qualifies(Long providerId, VehicleType vehicle) {
        return providerId != null && vehicle != null
                && capabilities.existsByDeliveryProviderIdAndVehicleType(providerId, vehicle);
    }

    /**
     * The smallest vehicle class this carrier is verified for that can take the weight, which is what to ask it to
     * quote; empty when it has none, in which case the carrier is not asked at all.
     */
    public Optional<VehicleType> vehicleFor(Long providerId, BigDecimal weightKg) {
        VehicleType minimum = VehicleType.fromWeight(weightKg);
        return capabilities.findByDeliveryProviderId(providerId).stream()
                .map(c -> c.getVehicleType())
                .filter(v -> v.ordinal() >= minimum.ordinal())
                .min(java.util.Comparator.naturalOrder());
    }
}
