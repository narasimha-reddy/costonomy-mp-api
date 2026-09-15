package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryProviderRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryProviderRepository extends JpaRepository<DeliveryProviderRecord, Long> {

    Optional<DeliveryProviderRecord> findByCode(String code);

    List<DeliveryProviderRecord> findByEnabledTrueOrderByPriorityAsc();
}
