package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.IntentAcceptanceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentAcceptanceItemRepository extends JpaRepository<IntentAcceptanceItem, Long> {

    List<IntentAcceptanceItem> findByIntentAcceptanceId(Long intentAcceptanceId);

    List<IntentAcceptanceItem> findByIntentAcceptanceIdIn(List<Long> acceptanceIds);
}
