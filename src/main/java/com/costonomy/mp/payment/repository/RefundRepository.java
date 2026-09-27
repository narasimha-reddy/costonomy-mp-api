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

    /**
     * Per payment of this outlet: wallet refunds credited, less what has already
     * been sent back to the card, in any state — a withdrawal stuck at the
     * provider has still left the wallet. Oldest credit first. D-104.
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select r.payment_id,
                   sum(case when r.destination = 'WALLET' and r.status = 'COMPLETED'
                            then r.amount else 0 end)
                 - sum(case when r.reason = 'WALLET_WITHDRAWAL' then r.amount else 0 end) as available,
                   min(case when r.destination = 'WALLET' then r.created_at end) as first_credit
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
