package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.IntentItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntentItemRepository extends JpaRepository<IntentItem, Long> {

    List<IntentItem> findByIntentIdOrderByIdAsc(Long intentId);

    List<IntentItem> findByIntentIdIn(List<Long> intentIds);

    Optional<IntentItem> findByIntentIdAndSupplierSkuId(Long intentId, Long supplierSkuId);

    long countByIntentId(Long intentId);
}
