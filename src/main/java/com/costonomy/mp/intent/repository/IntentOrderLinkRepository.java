package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.IntentOrderLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntentOrderLinkRepository extends JpaRepository<IntentOrderLink, Long> {

    Optional<IntentOrderLink> findByIntentId(Long intentId);

    /** The other direction: which request an order came from. */
    Optional<IntentOrderLink> findBySupplierOrderId(Long supplierOrderId);

    /** Batched, so a list of requests costs one query rather than one each. */
    List<IntentOrderLink> findByIntentIdIn(List<Long> intentIds);

}
