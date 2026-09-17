package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.IntentOrderLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntentOrderLinkRepository extends JpaRepository<IntentOrderLink, Long> {

    Optional<IntentOrderLink> findByIntentId(Long intentId);

    Optional<IntentOrderLink> findBySupplierOrderId(Long supplierOrderId);

    List<IntentOrderLink> findByIntentIdIn(List<Long> intentIds);

    boolean existsByIntentId(Long intentId);
}
