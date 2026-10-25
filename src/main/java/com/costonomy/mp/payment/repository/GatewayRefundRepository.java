package com.costonomy.mp.payment.repository;

import com.costonomy.mp.payment.domain.GatewayRefund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GatewayRefundRepository extends JpaRepository<GatewayRefund, Long> {

    Optional<GatewayRefund> findByIdempotencyKey(String idempotencyKey);

    List<GatewayRefund> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, Long sourceId);

    List<GatewayRefund> findByProviderPaymentIdOrderByIdAsc(String providerPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from GatewayRefund r where r.id = :id")
    Optional<GatewayRefund> lockById(@Param("id") Long id);

    /** The payment's reservation row, created on first use. A single-row upsert, so concurrent first requests serialise. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into gateway_refund_reservation (provider_payment_id, captured_amount, reserved_amount)
            values (:paymentId, :captured, 0)
            on duplicate key update provider_payment_id = provider_payment_id
            """, nativeQuery = true)
    int ensureReservation(@Param("paymentId") String paymentId, @Param("captured") BigDecimal captured);

    /** Raise what is reserved, only if it still fits under what was captured. 1 if it did, 0 if not. One statement. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update gateway_refund_reservation
               set reserved_amount = reserved_amount + :amount, version = version + 1, updated_at = now(6)
             where provider_payment_id = :paymentId and reserved_amount + :amount <= captured_amount
            """, nativeQuery = true)
    int reserve(@Param("paymentId") String paymentId, @Param("amount") BigDecimal amount);

    @Query(value = "select reserved_amount from gateway_refund_reservation where provider_payment_id = :paymentId",
            nativeQuery = true)
    BigDecimal reservedFor(@Param("paymentId") String paymentId);

    /** Ids to send: recorded a moment ago and not yet sent, or failed and due. */
    @Query(value = """
            select id from gateway_refund
             where status in ('REQUESTED', 'FAILED')
               and requested_at <= :olderThan
               and (next_retry_at is null or next_retry_at <= :now)
               and (claim_expires_at is null or claim_expires_at <= :now)
             order by id limit :limit
            """, nativeQuery = true)
    List<Long> readyToSend(@Param("now") Instant now, @Param("olderThan") Instant olderThan, @Param("limit") int limit);

    @Query(value = "select id from gateway_refund where status = 'SENDING' and claim_expires_at <= :now order by id limit :limit",
            nativeQuery = true)
    List<Long> expiredLeases(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "select id from gateway_refund where status = 'AMBIGUOUS' and (next_retry_at is null or next_retry_at <= :now) "
            + "order by id limit :limit", nativeQuery = true)
    List<Long> ambiguousToReconcile(@Param("now") Instant now, @Param("limit") int limit);

    /** Accepted by the gateway and not yet completed, due to be asked about. */
    @Query(value = "select id from gateway_refund where status = 'PENDING' and provider_refund_id is not null "
            + "and (next_retry_at is null or next_retry_at <= :now) order by id limit :limit", nativeQuery = true)
    List<Long> pendingToReconcile(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "select * from gateway_refund where status = 'PENDING' and requested_at < :before "
            + "order by requested_at, id limit :limit", nativeQuery = true)
    List<GatewayRefund> pendingOlderThan(@Param("before") Instant before, @Param("limit") int limit);

    @Query(value = "select count(*) from gateway_refund where status = 'PENDING' and requested_at < :before",
            nativeQuery = true)
    long countPendingOlderThan(@Param("before") Instant before);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update gateway_refund
               set status = 'SENDING', claim_token = :token, claim_expires_at = :leaseUntil,
                   attempt_count = attempt_count + 1, version = version + 1, updated_at = now(6)
             where id = :id and status in ('REQUESTED', 'FAILED')
               and (next_retry_at is null or next_retry_at <= :now)
               and (claim_expires_at is null or claim_expires_at <= :now)
            """, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("leaseUntil") Instant leaseUntil,
              @Param("now") Instant now);

    // ── queues (bounded) ───────────────────────────────────────────────────

    @Query(value = "select * from gateway_refund where status = :status order by updated_at, id limit :limit", nativeQuery = true)
    List<GatewayRefund> inStatus(@Param("status") String status, @Param("limit") int limit);

    @Query(value = "select * from gateway_refund where status in ('REQUESTED', 'FAILED') and requested_at < :before "
            + "order by requested_at, id limit :limit", nativeQuery = true)
    List<GatewayRefund> overdue(@Param("before") Instant before, @Param("limit") int limit);

    @Query(value = """
            select * from gateway_refund
             where (:status is null or status = :status) and (:source is null or source_type = :source)
             order by id desc
            """, countQuery = "select count(*) from gateway_refund where (:status is null or status = :status) "
            + "and (:source is null or source_type = :source)", nativeQuery = true)
    org.springframework.data.domain.Page<GatewayRefund> search(@Param("status") String status, @Param("source") String source,
                                                               org.springframework.data.domain.Pageable page);

    @Query(value = "select count(*) from gateway_refund where status in ('REQUESTED', 'FAILED') and requested_at < :before",
            nativeQuery = true)
    long countOverdue(@Param("before") Instant before);

    @Query(value = "select count(*) from gateway_refund where status = :status", nativeQuery = true)
    long countInStatus(@Param("status") String status);

    /** Customer money we owe back and have not yet confirmed returned. */
    @Query(value = "select coalesce(sum(amount), 0) from gateway_refund where status <> 'SUCCEEDED'", nativeQuery = true)
    BigDecimal sumUnfinished();
}
