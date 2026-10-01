package com.costonomy.mp.wallet.repository;

import com.costonomy.mp.wallet.domain.WalletTopUp;
import com.costonomy.mp.wallet.domain.WalletTopUpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WalletTopUpRepository extends JpaRepository<WalletTopUp, Long> {

    Optional<WalletTopUp> findByIdempotencyKey(String idempotencyKey);

    /**
     * The top-up, held until the transaction ends. Always taken <em>after</em> the
     * outlet's wallet lock, never before — every path that decides whether to
     * credit does both in that order, so none can wait on the other's lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WalletTopUp t where t.id = :id")
    Optional<WalletTopUp> lockById(@Param("id") Long id);

    /**
     * What this outlet has added this month, from top-ups that were actually
     * credited. A mock top-up has no row here and does not count: the limit is
     * about real money coming in (D-107).
     */
    @Query("""
            select coalesce(sum(t.amount), 0) from WalletTopUp t
             where t.outletId = :outletId and t.status = :credited
               and t.creditedAt >= :from and t.creditedAt < :to
            """)
    BigDecimal sumCredited(@Param("outletId") Long outletId,
                           @Param("credited") WalletTopUpStatus credited,
                           @Param("from") Instant from, @Param("to") Instant to);

    /** Record the Razorpay order once it exists. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletTopUp t set t.razorpayOrderId = :orderId, t.version = t.version + 1
             where t.id = :id and t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.CREATED
               and t.razorpayOrderId is null
            """)
    int attachOrder(@Param("id") Long id, @Param("orderId") String orderId);

    /**
     * CREATED or EXPIRED to CREDITED, once. The guard is in the {@code where}
     * clause, so of any number of callers exactly one gets 1 back — and the
     * caller that does credits the wallet in the same transaction (D-107).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletTopUp t
               set t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.CREDITED,
                   t.razorpayPaymentId = :paymentId, t.creditedAt = :now, t.version = t.version + 1
             where t.id = :id and t.status in (
                   com.costonomy.mp.wallet.domain.WalletTopUpStatus.CREATED,
                   com.costonomy.mp.wallet.domain.WalletTopUpStatus.EXPIRED)
            """)
    int markCredited(@Param("id") Long id, @Param("paymentId") String paymentId, @Param("now") Instant now);

    /** A captured payment that cannot be credited: it is to be returned instead. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletTopUp t
               set t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.REFUND_PENDING,
                   t.razorpayPaymentId = :paymentId, t.failureReason = :reason, t.version = t.version + 1
             where t.id = :id and t.status in (
                   com.costonomy.mp.wallet.domain.WalletTopUpStatus.CREATED,
                   com.costonomy.mp.wallet.domain.WalletTopUpStatus.EXPIRED)
            """)
    int markRefundPending(@Param("id") Long id, @Param("paymentId") String paymentId,
                          @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletTopUp t
               set t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.REFUNDED,
                   t.version = t.version + 1
             where t.id = :id and t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.REFUND_PENDING
            """)
    int markRefunded(@Param("id") Long id);

    /** Remember which provider refund is returning the money, and that we tried. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletTopUp t
               set t.providerRefundId = coalesce(:refundId, t.providerRefundId),
                   t.refundAttempts = t.refundAttempts + :attempts,
                   t.checkedAt = :now, t.version = t.version + 1
             where t.id = :id and t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.REFUND_PENDING
            """)
    int noteRefundAttempt(@Param("id") Long id, @Param("refundId") String refundId,
                          @Param("attempts") int attempts, @Param("now") Instant now);

    /** Only from CREATED: a credited or refunded top-up is never expired or failed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletTopUp t set t.status = :to, t.failureReason = :reason, t.version = t.version + 1
             where t.id = :id and t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.CREATED
            """)
    int endFromCreated(@Param("id") Long id, @Param("to") WalletTopUpStatus to,
                       @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WalletTopUp t set t.checkedAt = :now, t.version = t.version + 1 where t.id = :id")
    int markChecked(@Param("id") Long id, @Param("now") Instant now);

    /**
     * CREATED top-ups worth asking Razorpay about: at least {@code minAge} old, and
     * not asked too recently. A young one is asked about every minute — a lost
     * confirm shows up in the first few checks — an old one every half hour, so an
     * abandoned checkout costs about fifty calls over its day rather than 1,440.
     */
    @Query("""
            select t from WalletTopUp t
             where t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.CREATED
               and t.createdAt < :minAge
               and ((t.createdAt >= :youngAfter and (t.checkedAt is null or t.checkedAt < :youngRecheck))
                 or (t.createdAt < :youngAfter and (t.checkedAt is null or t.checkedAt < :oldRecheck)))
             order by t.createdAt
            """)
    List<WalletTopUp> findDue(@Param("minAge") Instant minAge, @Param("youngAfter") Instant youngAfter,
                              @Param("youngRecheck") Instant youngRecheck,
                              @Param("oldRecheck") Instant oldRecheck, Pageable page);

    /** Refunds still to send or to confirm, that have not run out of attempts. */
    @Query("""
            select t from WalletTopUp t
             where t.status = com.costonomy.mp.wallet.domain.WalletTopUpStatus.REFUND_PENDING
               and t.refundAttempts < :maxAttempts
               and (t.checkedAt is null or t.checkedAt < :recheck)
             order by t.createdAt
            """)
    List<WalletTopUp> findRefundsDue(@Param("maxAttempts") int maxAttempts,
                                     @Param("recheck") Instant recheck, Pageable page);

}
