package com.costonomy.mp.payment.repository;

import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findBySupplierOrderId(Long supplierOrderId);

    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    /**
     * The payment, locked for the rest of the transaction.
     *
     * <p>Every change of payment state goes through this first (D-099). Without it,
     * a confirm and a webhook for the same payment each inserted a ledger row —
     * taking a shared lock on the payment through the foreign key — and then both
     * asked for the exclusive lock to update it: a deadlock, and a 500 for a
     * customer who had paid. Locking first makes the second writer wait, then see
     * the first one's answer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);

    /** The order's payment, locked. For writers that start from the order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.supplierOrderId = :supplierOrderId")
    Optional<Payment> lockBySupplierOrderId(@Param("supplierOrderId") Long supplierOrderId);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    /**
     * Payments made after the given one that the provider took money for, newest first: what the proof that the
     * provider's keys work is read from when a payment the provider does not know is being decided (D-110). Not
     * the payment itself, not one already found to be unknown, and only this provider's own.
     */
    @Query("""
            select p from Payment p
            where p.provider = :provider and p.providerPaymentId is not null and p.id > :afterId
              and p.status in (com.costonomy.mp.payment.domain.PaymentStatus.CAPTURED,
                               com.costonomy.mp.payment.domain.PaymentStatus.PARTIALLY_REFUNDED,
                               com.costonomy.mp.payment.domain.PaymentStatus.FULLY_REFUNDED)
              and (p.providerRefundBlockedReason is null or p.providerRefundBlockedReason <> 'PAYMENT_UNKNOWN')
            order by p.id desc
            """)
    List<Payment> findKeyProofCandidates(@Param("provider") String provider, @Param("afterId") Long afterId,
                                         Pageable page);

    List<Payment> findByProcurementId(Long procurementId);

    List<Payment> findByStatusIn(Collection<PaymentStatus> statuses);

    /**
     * Payments waiting to be captured. Read by the capture job.
     *
     * <p>Ordered oldest first: a capture that has been pending longest is the one
     * closest to the provider's authorisation window expiring, and an authorisation
     * that lapses before capture is money the supplier will never receive.
     */
    @Query("""
            select p from Payment p
            where p.status = com.costonomy.mp.payment.domain.PaymentStatus.CAPTURE_PENDING
            order by p.updatedAt asc
            """)
    List<Payment> findPendingCaptures(Pageable batch);

    /**
     * Cancelled orders whose debited money is still on its way back (D-109). Read by
     * the cancellation job, least recently worked first, so one payment the provider
     * keeps refusing cannot starve the rest. A payment a person has been asked to
     * look at is skipped until they have.
     */
    @Query("""
            select p from Payment p
            where p.status = com.costonomy.mp.payment.domain.PaymentStatus.CANCEL_PENDING
              and p.reviewRequiredAt is null
            order by p.updatedAt asc, p.id asc
            """)
    List<Payment> findPendingCancellations(Pageable batch);

    /**
     * Cancelled orders whose return a person was asked to look at (D-109), least recently
     * checked first. Read by the cancellation job to re-check them slowly, and to keep
     * reminding: a payment in review is not forgotten, and can still turn out to have been
     * returned by the provider itself.
     */
    @Query("""
            select p from Payment p
            where p.status = com.costonomy.mp.payment.domain.PaymentStatus.CANCEL_PENDING
              and p.reviewRequiredAt is not null
            order by coalesce(p.reconciledAt, p.reviewRequiredAt) asc, p.id asc
            """)
    List<Payment> findReviewedCancellations(Pageable batch);

    /**
     * Authorisations that were never resolved. Read by the reconciliation job
     * (doc 21, doc 38).
     *
     * <p>Catches doc 46's lost callback: the customer paid, the client vanished,
     * and nothing told us. Asking the provider is the only way to find out.
     *
     * <p>Least recently asked first (D-101). Ordered by {@code updated_at}, rows
     * the sweep skipped without writing stayed at the front and, 200 of them,
     * filled every batch for good — no lost payment was ever found again.
     */
    @Query("""
            select p from Payment p
            where p.status in (com.costonomy.mp.payment.domain.PaymentStatus.CREATED,
                               com.costonomy.mp.payment.domain.PaymentStatus.AUTHORIZED)
              and p.updatedAt < :staleBefore
            order by coalesce(p.reconciledAt, p.createdAt) asc, p.id asc
            """)
    List<Payment> findStale(@Param("staleBefore") Instant staleBefore, Pageable batch);

    /**
     * Note that we asked the provider about this payment.
     *
     * <p>One column, one statement: the sweep holds a detached copy, and saving it
     * whole could overwrite a confirm that landed meanwhile. The row's updated_at
     * moves with it, which is what spaces out the next sweep.
     */
    @Transactional
    @Modifying
    @Query("update Payment p set p.reconciledAt = :at where p.id = :id")
    int markAsked(@Param("id") Long id, @Param("at") Instant at);

    /**
     * Stop drawing withdrawals from this payment (D-110). One conditional statement, so of two
     * callers that meet the same refusal one records it, and only that one audits it.
     *
     * @return 1 if this call blocked it, 0 if it already was
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.providerRefundBlockedAt = :at, p.providerRefundBlockedReason = :reason,
                   p.version = p.version + 1, p.updatedAt = :at
             where p.id = :id and p.providerRefundBlockedAt is null
            """)
    int block(@Param("id") Long id, @Param("at") Instant at, @Param("reason") String reason);

    /** Undo a block (D-110). @return 1 if it was blocked and now is not */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.providerRefundBlockedAt = null, p.providerRefundBlockedReason = null,
                   p.version = p.version + 1, p.updatedAt = :at
             where p.id = :id and p.providerRefundBlockedAt is not null
            """)
    int unblock(@Param("id") Long id, @Param("at") Instant at);
}
