package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.IntentAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IntentAcceptanceRepository extends JpaRepository<IntentAcceptance, Long> {

    Optional<IntentAcceptance> findByIntentId(Long intentId);

    List<IntentAcceptance> findByIntentIdIn(List<Long> intentIds);

    @Query("""
            select a from IntentAcceptance a
            where a.status = 'SUBMITTED'
              and a.expiresAt < :now
            """)
    List<IntentAcceptance> findExpired(@Param("now") Instant now);
}
