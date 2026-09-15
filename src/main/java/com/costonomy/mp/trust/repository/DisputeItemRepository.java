package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.DisputeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeItemRepository extends JpaRepository<DisputeItem, Long> {

    List<DisputeItem> findByDisputeIdOrderByIdAsc(Long disputeId);
}
