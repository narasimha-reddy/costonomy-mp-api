package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryLedgerRepository extends JpaRepository<DeliveryLedgerEntry, Long> {

    List<DeliveryLedgerEntry> findByDeliveryIdOrderByCreatedAtAsc(Long deliveryId);
}
