package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.IntentAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * There is deliberately no "find expired acceptances" query.
 *
 * <p>An acceptance expires at the same instant as the intent's order-creation
 * deadline — they are one clock — so {@code IntentTransitions} marks both in one
 * transaction. A separate sweep would be a second thing that could decide an
 * offer had lapsed, and the two could disagree.
 */
public interface IntentAcceptanceRepository extends JpaRepository<IntentAcceptance, Long> {

    Optional<IntentAcceptance> findByIntentId(Long intentId);

    List<IntentAcceptance> findByIntentIdIn(List<Long> intentIds);
}
