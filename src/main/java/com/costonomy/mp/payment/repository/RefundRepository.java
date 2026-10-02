package com.costonomy.mp.payment.repository;

import com.costonomy.mp.payment.domain.Refund;
import com.costonomy.mp.payment.domain.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(Long paymentId);

    List<Refund> findByStatusIn(Collection<RefundStatus> statuses);

    /** Refunds claimed for sending and never finished — the process died mid-call. */
    List<Refund> findByStatusAndProviderRefundIdIsNullAndUpdatedAtBefore(RefundStatus status, Instant before);

    /** Accepted by the provider but not finished there yet: to be asked about. */
    List<Refund> findByStatusAndProviderRefundIdIsNotNullAndUpdatedAtBefore(RefundStatus status, Instant before);

    /**
     * Money promised back on a payment but not yet returned (D-101). Counted
     * against what can still be refunded, or two requests could each claim the
     * whole capture.
     */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(r.amount), 0) from Refund r
            where r.paymentId = :paymentId and r.status in :statuses
            """)
    BigDecimal sumByPaymentIdAndStatusIn(
            @org.springframework.data.repository.query.Param("paymentId") Long paymentId,
            @org.springframework.data.repository.query.Param("statuses") Collection<RefundStatus> statuses);
}
