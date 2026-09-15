package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.DisputeMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeMessageRepository extends JpaRepository<DisputeMessage, Long> {

    List<DisputeMessage> findByDisputeIdOrderByCreatedAtAscIdAsc(Long disputeId);
}
