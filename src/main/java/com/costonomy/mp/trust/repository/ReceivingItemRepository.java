package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.ReceivingItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReceivingItemRepository extends JpaRepository<ReceivingItem, Long> {

    List<ReceivingItem> findByReceivingIdOrderByIdAsc(Long receivingId);
}
