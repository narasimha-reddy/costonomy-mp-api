package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPayRefundReason;
import com.costonomy.mp.credit.domain.CreditPaymentRefund;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Refunds of credit payments (D-199). Sent by a job; every change is a conditional update. */
public interface CreditPaymentRefundRepository extends JpaRepository<CreditPaymentRefund, Long> {

    List<CreditPaymentRefund> findByAttemptIdOrderByIdAsc(Long attemptId);

    @Query("select r from CreditPaymentRefund r where r.razorpayPaymentId = :paymentId and r.reason = :reason")
    Optional<CreditPaymentRefund> findByRazorpayPaymentIdAndReason(@Param("paymentId") String paymentId,
                                                                    @Param("reason") CreditPayRefundReason reason);

    /** PENDING, fewer than {@code maxAttempts} failed sends, not tried since {@code triedBefore}; oldest first. */
    @Query("""
            select r from CreditPaymentRefund r
             where r.status = com.costonomy.mp.credit.domain.CreditPayRefundStatus.PENDING
               and r.attempts < :maxAttempts and (r.lastTriedAt is null or r.lastTriedAt < :triedBefore)
             order by r.id
            """)
    List<CreditPaymentRefund> findDue(@Param("maxAttempts") int maxAttempts,
                                      @Param("triedBefore") Instant triedBefore, Pageable page);

    /** Records a send: the provider refund id (kept once set), failed attempts added, when, and why. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CreditPaymentRefund r
               set r.providerRefundId = coalesce(r.providerRefundId, :providerRefundId),
                   r.attempts = r.attempts + :addAttempts, r.lastTriedAt = :at, r.failureReason = :failureReason,
                   r.version = r.version + 1
             where r.id = :id
            """)
    int noteAttempt(@Param("id") Long id, @Param("providerRefundId") String providerRefundId,
                    @Param("addAttempts") int addAttempts, @Param("at") Instant at,
                    @Param("failureReason") String failureReason);

    /** PENDING to COMPLETED, once. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CreditPaymentRefund r
               set r.status = com.costonomy.mp.credit.domain.CreditPayRefundStatus.COMPLETED,
                   r.completedAt = :at, r.version = r.version + 1
             where r.id = :id and r.status = com.costonomy.mp.credit.domain.CreditPayRefundStatus.PENDING
            """)
    int markCompleted(@Param("id") Long id, @Param("at") Instant at);
}
