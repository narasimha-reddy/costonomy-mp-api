package com.costonomy.mp.quickscan.repository;

import com.costonomy.mp.quickscan.domain.QuickScanPayment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface QuickScanPaymentRepository extends JpaRepository<QuickScanPayment, Long> {

    Optional<QuickScanPayment> findByIdempotencyKey(String idempotencyKey);

    List<QuickScanPayment> findByOutletIdOrderByCreatedAtDesc(Long outletId, Pageable pageable);

    /**
     * Ready for the <b>job</b> to send: claimed by a process that died before
     * recording an outcome — PAYOUT_PENDING, no provider id, not updated since
     * {@code stuckBefore} — or a fresh row (zero attempts) old enough that the
     * request that created it must already have finished, one way or another —
     * created before {@code freshBefore}.
     *
     * <p>That age guard on the fresh case is the whole fix for a restaurant
     * charged and then told "error": {@code payFromWallet} commits the wallet
     * debit and then makes its own synchronous send, claiming the row itself by
     * bumping {@code attempts} to 1. A row still at zero attempts less than
     * {@code freshBefore} old is a request that has not reached that claim yet —
     * the job must leave it alone, or it can win the claim first and make the
     * request's own {@code saveAndFlush} lose the optimistic-lock race.
     * {@code QuickScanService.claimable} is the request's own, separate check,
     * and rightly has no such delay — it is the process about to do the send.
     */
    @Query("""
            select p from QuickScanPayment p
             where p.status = 'PAYOUT_PENDING'
               and p.providerPayoutId is null
               and ((p.attempts = 0 and p.createdAt < :freshBefore) or p.updatedAt < :stuckBefore)
            """)
    List<QuickScanPayment> findClaimable(
            @Param("freshBefore") Instant freshBefore, @Param("stuckBefore") Instant stuckBefore, Pageable pageable);

    /**
     * Sent to the provider and due another check: PAYOUT_PENDING not checked (or
     * touched) since {@code pendingBefore}, or PAID within {@code paidWatchAfter}
     * and not checked (or paid) since {@code paidBefore} — a PAID payout can
     * still come back REVERSED, so it is asked about too, but only a few times
     * over a bounded window, not on every run forever (D-106).
     */
    @Query("""
            select p from QuickScanPayment p
             where p.providerPayoutId is not null
               and ((p.status = com.costonomy.mp.quickscan.domain.QuickScanStatus.PAYOUT_PENDING
                     and coalesce(p.checkedAt, p.updatedAt) < :pendingBefore)
                 or (p.status = com.costonomy.mp.quickscan.domain.QuickScanStatus.PAID
                     and p.paidAt > :paidWatchAfter
                     and coalesce(p.checkedAt, p.paidAt) < :paidBefore))
            """)
    List<QuickScanPayment> findSettleable(
            @Param("pendingBefore") Instant pendingBefore,
            @Param("paidWatchAfter") Instant paidWatchAfter,
            @Param("paidBefore") Instant paidBefore,
            Pageable pageable);
}
