package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPaymentAttempt;
import com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Credit payment attempts (D-196). Transitions out of CREATED are conditional updates, as for wallet top-ups. */
public interface CreditPaymentAttemptRepository extends JpaRepository<CreditPaymentAttempt, Long> {

    Optional<CreditPaymentAttempt> findByIdempotencyKey(String key);

    Optional<CreditPaymentAttempt> findByOpenSelectionKey(String key);

    Optional<CreditPaymentAttempt> findByRazorpayOrderId(String orderId);

    /**
     * The attempt, held until the transaction ends. Every path that applies a capture takes this first, then the
     * invoices ascending, then the agreements ascending; the wallet path inserts its attempt after the wallet lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CreditPaymentAttempt a where a.id = :id")
    Optional<CreditPaymentAttempt> lockById(@Param("id") Long id);

    /** Record the Razorpay order once it exists. Status CREATED and no order yet; returns rows changed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CreditPaymentAttempt a set a.razorpayOrderId = :orderId, a.version = a.version + 1
             where a.id = :id and a.status = com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus.CREATED
               and a.razorpayOrderId is null
            """)
    int attachOrder(@Param("id") Long id, @Param("orderId") String razorpayOrderId);

    /**
     * Only from CREATED (a paid or refunded attempt is never expired or failed): to {@code to} (FAILED or EXPIRED),
     * frees the open selection, sets the reason, and the expiry time when EXPIRED.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CreditPaymentAttempt a
               set a.status = :to, a.openSelectionKey = null, a.failureReason = :reason,
                   a.expiredAt = case when :to = com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus.EXPIRED
                                      then :at else a.expiredAt end,
                   a.version = a.version + 1
             where a.id = :id and a.status = com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus.CREATED
            """)
    int endFromCreated(@Param("id") Long id, @Param("to") CreditPaymentAttemptStatus to,
                       @Param("reason") String reason, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CreditPaymentAttempt a set a.checkedAt = :at, a.version = a.version + 1 where a.id = :id")
    int markChecked(@Param("id") Long id, @Param("at") Instant at);

    /** Only where it was not alerted before, so the alert is logged once. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CreditPaymentAttempt a set a.stuckAlertedAt = :at, a.version = a.version + 1
             where a.id = :id and a.stuckAlertedAt is null
            """)
    int markStuckAlerted(@Param("id") Long id, @Param("at") Instant at);

    /**
     * ONLINE attempts with an order id that are worth asking Razorpay about: CREATED older than {@code createdBefore}
     * and not asked since {@code recheckBefore}; or EXPIRED, created after {@code expiredSince}, and not asked since
     * {@code lateRecheckBefore} (a payment whose webhook was lost is still applied). Oldest first.
     */
    @Query("""
            select a from CreditPaymentAttempt a
             where a.channel = com.costonomy.mp.credit.domain.CreditPayChannel.ONLINE and a.razorpayOrderId is not null
               and ((a.status = com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus.CREATED
                     and a.createdAt < :createdBefore
                     and (a.checkedAt is null or a.checkedAt < :recheckBefore))
                 or (a.status = com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus.EXPIRED
                     and a.createdAt > :expiredSince
                     and (a.checkedAt is null or a.checkedAt < :lateRecheckBefore)))
             order by a.createdAt
            """)
    List<CreditPaymentAttempt> findDueForPolling(@Param("createdBefore") Instant createdBefore,
                                                 @Param("recheckBefore") Instant recheckBefore,
                                                 @Param("expiredSince") Instant expiredSince,
                                                 @Param("lateRecheckBefore") Instant lateRecheckBefore,
                                                 Pageable page);

    /** CREATED, never given an order id, older than {@code createdBefore}. */
    @Query("""
            select a from CreditPaymentAttempt a
             where a.status = com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus.CREATED
               and a.razorpayOrderId is null and a.createdAt < :createdBefore
             order by a.createdAt
            """)
    List<CreditPaymentAttempt> findNeverOpened(@Param("createdBefore") Instant createdBefore, Pageable page);
}
