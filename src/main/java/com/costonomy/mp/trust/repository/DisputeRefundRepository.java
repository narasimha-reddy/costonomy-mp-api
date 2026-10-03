package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.DisputeRefund;
import com.costonomy.mp.trust.domain.DisputeRefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DisputeRefundRepository extends JpaRepository<DisputeRefund, Long> {

    Optional<DisputeRefund> findByDisputeId(Long disputeId);

    List<DisputeRefund> findByDisputeIdIn(Collection<Long> disputeIds);

    /**
     * Held until the transaction ends, so a supplier and an operator deciding the
     * same request at once are decided one after the other, and the second finds
     * it already decided rather than paying it again.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DisputeRefund r where r.id = :id")
    Optional<DisputeRefund> lockById(@Param("id") Long id);

    /** What operations may decide: declined, or unanswered past the supplier's window. */
    @Query("""
            select r from DisputeRefund r
             where r.status = com.costonomy.mp.trust.domain.DisputeRefundStatus.DECLINED
                or (r.status = com.costonomy.mp.trust.domain.DisputeRefundStatus.REQUESTED
                    and r.createdAt < :unansweredBefore)
             order by r.createdAt
            """)
    List<DisputeRefund> findEscalated(@Param("unansweredBefore") Instant unansweredBefore);

    List<DisputeRefund> findByStatusIn(Collection<DisputeRefundStatus> statuses);
}
