package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryQuoteRepository extends JpaRepository<DeliveryQuote, Long> {

    List<DeliveryQuote> findByDeliveryIdOrderByIdAsc(Long deliveryId);
}
