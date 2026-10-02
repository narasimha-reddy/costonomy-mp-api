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

    /**
     * Oldest attempt first (then oldest row), which is what makes the refund run fair. Every send
     * moves {@code updated_at}, so a refund that was just tried, and backed off, goes to the back
     * and the next run starts with another one. Without the order MySQL returns FAILED rows ahead
     * of REQUESTED ones, in the same order every time, and a refund the provider keeps refusing
     * (which stops the run) would be first for ever and starve every other.
     */
    List<Refund> findByStatusInOrderByUpdatedAtAscIdAsc(Collection<RefundStatus> statuses);

    /** Refunds claimed for sending and never finished — the process died mid-call. */
    List<Refund> findByStatusAndProviderRefundIdIsNullAndUpdatedAtBefore(RefundStatus status, Instant before);

    /** Accepted by the provider but not finished there yet: to be asked about. */
    List<Refund> findByStatusAndProviderRefundIdIsNotNullAndUpdatedAtBefore(RefundStatus status, Instant before);

    /**
     * Money promised back on a payment but not yet returned (D-101). Counted
     * against what can still be refunded, or two requests could each claim the
     * whole capture.
     *
     * <p>Not withdrawals (D-104): their money was counted as refunded when it
     * reached the wallet, and counting it here as well would refuse a refund the
     * payment can still cover.
     */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(r.amount), 0) from Refund r
            where r.paymentId = :paymentId and r.status in :statuses
              and r.reason <> com.costonomy.mp.payment.domain.RefundReason.WALLET_WITHDRAWAL
            """)
    BigDecimal sumByPaymentIdAndStatusIn(
            @org.springframework.data.repository.query.Param("paymentId") Long paymentId,
            @org.springframework.data.repository.query.Param("statuses") Collection<RefundStatus> statuses);

    /** The refund, held until the transaction ends (D-110). Lock order: wallet, then this, then the payment. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Refund r where r.id = :id")
    Optional<Refund> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    /** Refunds on this payment in this state that failed the given way: how often the same unexplained refusal has come (D-110). */
    long countByPaymentIdAndStatusAndFailureKind(Long paymentId, RefundStatus status,
                                                  com.costonomy.mp.payment.provider.ProviderFailureKind kind);

    /**
     * What parts of ours on this payment, completed, are recorded as closed by this refund made outside Mandi
     * ({@code review_cause}, {@code review_ref}); never more than that refund's own amount (D-110).
     */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(r.amount), 0) from Refund r
            where r.paymentId = :paymentId and r.reviewCause = :cause and r.reviewRef = :providerRefundId
              and r.status = com.costonomy.mp.payment.domain.RefundStatus.COMPLETED
            """)
    BigDecimal claimedAgainst(@org.springframework.data.repository.query.Param("paymentId") Long paymentId,
                              @org.springframework.data.repository.query.Param("cause") String cause,
                              @org.springframework.data.repository.query.Param("providerRefundId") String providerRefundId);

    /** Whether another refund of ours already claims this provider refund: one provider refund completes one of ours (D-110). */
    boolean existsByProviderRefundIdAndIdNot(String providerRefundId, Long id);

    /**
     * The rejected refunds the run reads, the one read least recently first: by the time it was last read and
     * left undecided, or, never, by when it was rejected. Without that, two refunds the provider keeps saying
     * it cannot place would be first every run and every other rejected refund would wait behind them (D-110).
     */
    @org.springframework.data.jpa.repository.Query("""
            select r from Refund r
            where r.status = com.costonomy.mp.payment.domain.RefundStatus.REJECTED
            order by coalesce(r.settleHeldAt, r.updatedAt), r.id
            """)
    List<Refund> rejectedToSettle(org.springframework.data.domain.Pageable page);

    /** Rejected refunds waiting longer than {@code before}, a page after {@code afterId}: what the alert reads, whatever the run got to (D-110). */
    @org.springframework.data.jpa.repository.Query("""
            select r from Refund r
            where r.status = com.costonomy.mp.payment.domain.RefundStatus.REJECTED
              and r.updatedAt < :before and r.id > :afterId
            order by r.id
            """)
    List<Refund> rejectedSince(
            @org.springframework.data.repository.query.Param("before") Instant before,
            @org.springframework.data.repository.query.Param("afterId") Long afterId,
            org.springframework.data.domain.Pageable page);

    /**
     * Note that these rejected refunds were read and left undecided, so the queue moves them behind the others.
     * {@code updated_at} is kept as it is: it is when they were rejected, which the alert counts from.
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Refund r set r.settleHeldAt = :at, r.updatedAt = r.updatedAt
            where r.id in :ids and r.status = com.costonomy.mp.payment.domain.RefundStatus.REJECTED
            """)
    int markHeld(@org.springframework.data.repository.query.Param("ids") Collection<Long> ids,
                 @org.springframework.data.repository.query.Param("at") Instant at);

    /**
     * Refunds refused by the provider for a reason that is about our account, not the refund, since a
     * moment ago: what trips the withdrawal circuit breaker (D-110). Any state, because a refund that
     * failed for a missing balance and was then put back is exactly the one that must still count.
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(r) from Refund r
            where r.failureKind in :kinds and r.updatedAt >= :since
            """)
    long countFailuresSince(
            @org.springframework.data.repository.query.Param("kinds") Collection<com.costonomy.mp.payment.provider.ProviderFailureKind> kinds,
            @org.springframework.data.repository.query.Param("since") Instant since);

    /**
     * What we may have sent to the provider that the payment's {@code amount_refunded}, read at
     * {@code cutoff}, might not yet include (D-110). Counted against what the provider says is left, so a
     * withdrawal is never allowed more than can go back. Withdrawals and cancellation refunds alike.
     *
     * <p>An over-estimate is the safe side: it can only lower what a withdrawal is offered while one of
     * ours is still in flight. So every refund of ours that has not finished counts, whether or not it has a
     * provider id yet (a pending refund with one may not be in {@code amount_refunded} either), and so does
     * one that finished or was last changed since the read: {@code updated_at} moves at every send, which
     * {@code sent_at} (the first) does not, so a refund sent again after an ambiguous answer is counted.
     */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(r.amount), 0) from Refund r
            where r.paymentId = :paymentId
              and r.destination = com.costonomy.mp.payment.domain.RefundDestination.ORIGINAL
              and r.status <> com.costonomy.mp.payment.domain.RefundStatus.REVERSED
              and (r.status in (com.costonomy.mp.payment.domain.RefundStatus.REQUESTED,
                                com.costonomy.mp.payment.domain.RefundStatus.PROCESSING,
                                com.costonomy.mp.payment.domain.RefundStatus.FAILED,
                                com.costonomy.mp.payment.domain.RefundStatus.REJECTED,
                                com.costonomy.mp.payment.domain.RefundStatus.NEEDS_REVIEW)
                   or r.updatedAt >= :cutoff)
            """)
    BigDecimal unconfirmedOn(
            @org.springframework.data.repository.query.Param("paymentId") Long paymentId,
            @org.springframework.data.repository.query.Param("cutoff") Instant cutoff);

    /** Refunds the provider refused for good, looked at by a person, and possibly refunded since: read again (D-110). */
    @org.springframework.data.jpa.repository.Query("""
            select r from Refund r
            where r.status = com.costonomy.mp.payment.domain.RefundStatus.NEEDS_REVIEW
              and r.failureKind = com.costonomy.mp.payment.provider.ProviderFailureKind.AMBIGUOUS
              and r.updatedAt >= :notBefore
              and (r.verifiedAt is null or r.verifiedAt < :verifiedBefore)
            order by r.updatedAt
            """)
    List<Refund> ambiguousToReconcile(
            @org.springframework.data.repository.query.Param("notBefore") Instant notBefore,
            @org.springframework.data.repository.query.Param("verifiedBefore") Instant verifiedBefore,
            org.springframework.data.domain.Pageable page);

    /**
     * Reversed refunds still inside the window in which a late success is watched for and not yet found (D-110), a
     * page after {@code afterId}. Which of them is due at this moment is decided from the schedule, by
     * {@code WithdrawalReversalService.auditDue}, so the job walks every page: a fixed first page of not-yet-due
     * refunds would starve the recent ones, which are the ones due soonest.
     */
    @org.springframework.data.jpa.repository.Query("""
            select r from Refund r
            where r.status = com.costonomy.mp.payment.domain.RefundStatus.REVERSED
              and r.reversedAt >= :notBefore and r.lateSuccessAt is null and r.id > :afterId
            order by r.id
            """)
    List<Refund> reversedToAudit(
            @org.springframework.data.repository.query.Param("notBefore") Instant notBefore,
            @org.springframework.data.repository.query.Param("afterId") Long afterId,
            org.springframework.data.domain.Pageable page);

    /** Refunds that were put back and then turned up at the provider, not yet resolved by a person (D-110). */
    @org.springframework.data.jpa.repository.Query("""
            select r from Refund r
            where r.lateSuccessAt is not null and r.lateSuccessResolvedAt is null
            order by r.lateSuccessAt
            """)
    List<Refund> lateSuccessOpen(org.springframework.data.domain.Pageable page);

    /** How many double credits of this outlet are waiting for a person: while there is one, its withdrawals are paused (D-110). */
    @org.springframework.data.jpa.repository.Query("""
            select count(r) from Refund r, Payment p
            where p.id = r.paymentId and p.outletId = :outletId
              and r.lateSuccessAt is not null and r.lateSuccessResolvedAt is null
            """)
    long countLateSuccessOpen(@org.springframework.data.repository.query.Param("outletId") Long outletId);

    /**
     * Record that the provider's list was read for this refund, without touching its status or version, so
     * a check never races a decision. {@code updated_at} is set to itself: the column moves on any change
     * (ON UPDATE), and it is the clock of the last decision, which the breaker and the review window read.
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Refund r set r.verifiedAt = :at, r.verifiedResult = :result, r.updatedAt = r.updatedAt
            where r.id = :id
            """)
    int markVerified(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("at") Instant at,
                     @org.springframework.data.repository.query.Param("result") String result);

    /** Refunds by status, newest first, for operations. */
    List<Refund> findByStatusInOrderByUpdatedAtDesc(Collection<RefundStatus> statuses,
                                                    org.springframework.data.domain.Pageable page);

    /**
     * Per payment of this outlet: wallet refunds credited, less what has already
     * been sent back to the card, in any state but reversed — a withdrawal stuck at
     * the provider has still left the wallet, but one that was put back has not
     * (D-110). Oldest credit first. D-104. The last column says whether the payment is
     * blocked as a refund source, so the caller can tell blocked money from spent.
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select r.payment_id,
                   sum(case when r.destination = 'WALLET' and r.status = 'COMPLETED'
                            then r.amount else 0 end)
                 - sum(case when r.reason = 'WALLET_WITHDRAWAL' and r.status <> 'REVERSED'
                            then r.amount else 0 end) as available,
                   min(case when r.destination = 'WALLET' then r.created_at end) as first_credit,
                   max(p.provider_refund_blocked_at is not null) as blocked
              from refund r
              join payment p on p.id = r.payment_id
             where p.outlet_id = :outletId
             group by r.payment_id
            having available > 0
             order by first_credit, r.payment_id
            """)
    List<Object[]> withdrawableByPayment(
            @org.springframework.data.repository.query.Param("outletId") Long outletId);
}
