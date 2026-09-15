package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.DisputeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, Long> {

    List<DisputeEvidence> findByDisputeIdOrderByIdAsc(Long disputeId);
}
