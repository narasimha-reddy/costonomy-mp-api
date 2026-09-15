package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryProviderAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryProviderAttemptRepository
        extends JpaRepository<DeliveryProviderAttempt, Long> {

    List<DeliveryProviderAttempt> findByDeliveryIdOrderByAttemptNumberAsc(Long deliveryId);

    Optional<DeliveryProviderAttempt> findFirstByDeliveryIdOrderByAttemptNumberDesc(Long deliveryId);
}
