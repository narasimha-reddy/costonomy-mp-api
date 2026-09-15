package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryEventRepository extends JpaRepository<DeliveryEvent, Long> {

    List<DeliveryEvent> findByDeliveryIdOrderByOccurredAtAscIdAsc(Long deliveryId);
}
