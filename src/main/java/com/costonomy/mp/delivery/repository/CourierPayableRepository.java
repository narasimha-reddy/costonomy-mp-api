package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.CourierPayable;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CourierPayableRepository extends JpaRepository<CourierPayable, Long> {

    Optional<CourierPayable> findBySourceTypeAndDeliveryIdAndAttemptId(String sourceType, Long deliveryId, Long attemptId);

    List<CourierPayable> findBySourceTypeAndDeliveryIdOrderByIdAsc(String sourceType, Long deliveryId);

    List<CourierPayable> findBySupplierOrderIdOrderByIdAsc(Long supplierOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CourierPayable p where p.id = :id")
    Optional<CourierPayable> lockById(@Param("id") Long id);

    /**
     * Ids ready to pay: due or failed, on an automatic rail, past their retry time, with no live lease. Bounded.
     */
    @Query(value = """
            select id from courier_payable
             where state in ('DUE', 'FAILED')
               and payment_rail is not null and payment_rail <> 'MANUAL'
               and (next_retry_at is null or next_retry_at <= :now)
               and (claim_expires_at is null or claim_expires_at <= :now)
             order by coalesce(next_retry_at, due_at), id
             limit :limit
            """, nativeQuery = true)
    List<Long> readyToPay(@Param("now") Instant now, @Param("limit") int limit);

    /** Leases that ran out while PROCESSING: the worker died or lost the answer, so the payment may have gone. */
    @Query(value = """
            select id from courier_payable
             where state = 'PROCESSING' and claim_expires_at <= :now
             order by id limit :limit
            """, nativeQuery = true)
    List<Long> expiredLeases(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            select id from courier_payable
             where state = 'AMBIGUOUS' and (next_retry_at is null or next_retry_at <= :now)
             order by id limit :limit
            """, nativeQuery = true)
    List<Long> ambiguousToReconcile(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Claim for payment: one conditional update, so two workers cannot both take it. Returns 1 if this caller won.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update courier_payable
               set state = 'PROCESSING', claim_token = :token, claim_expires_at = :leaseUntil,
                   attempt_count = attempt_count + 1, version = version + 1, updated_at = now(6)
             where id = :id and state in ('DUE', 'FAILED')
               and payment_rail is not null and payment_rail <> 'MANUAL'
               and (next_retry_at is null or next_retry_at <= :now)
               and (claim_expires_at is null or claim_expires_at <= :now)
            """, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("leaseUntil") Instant leaseUntil,
              @Param("now") Instant now);

    // ── queues for finance (bounded) ──────────────────────────────────────────

    @Query(value = "select * from courier_payable where state in ('DUE', 'FAILED') and due_at < :before "
            + "order by due_at, id limit :limit", nativeQuery = true)
    List<CourierPayable> overdue(@Param("before") Instant before, @Param("limit") int limit);

    @Query(value = "select * from courier_payable where state = :state order by updated_at, id limit :limit",
            nativeQuery = true)
    List<CourierPayable> inState(@Param("state") String state, @Param("limit") int limit);

    @Query(value = "select * from courier_payable where reconciliation_state = 'REQUIRED' order by updated_at, id limit :limit",
            nativeQuery = true)
    List<CourierPayable> unreconciled(@Param("limit") int limit);

    @Query(value = """
            select * from courier_payable
             where (:state is null or state = :state)
               and (:provider is null or provider_code = :provider)
               and (:source is null or source_type = :source)
             order by id desc
            """, countQuery = "select count(*) from courier_payable where (:state is null or state = :state) "
            + "and (:provider is null or provider_code = :provider) and (:source is null or source_type = :source)",
            nativeQuery = true)
    org.springframework.data.domain.Page<CourierPayable> search(@Param("state") String state,
                                                                @Param("provider") String provider,
                                                                @Param("source") String source, Pageable page);

    @Query(value = "select * from courier_payable where state in ('DUE', 'FAILED') and payment_rail = 'MANUAL' "
            + "order by due_at, id limit :limit", nativeQuery = true)
    List<CourierPayable> awaitingManualPayment(@Param("limit") int limit);

    @Query(value = "select count(*) from courier_payable where state in ('DUE', 'FAILED') and payment_rail = 'MANUAL'",
            nativeQuery = true)
    long countAwaitingManualPayment();

    @Query(value = "select count(*) from courier_payable where state in ('DUE', 'FAILED') and due_at < :before",
            nativeQuery = true)
    long countOverdue(@Param("before") Instant before);

    @Query(value = "select count(*) from courier_payable where state = :state", nativeQuery = true)
    long countInState(@Param("state") String state);

    @Query(value = "select count(*) from courier_payable where reconciliation_state = 'REQUIRED'", nativeQuery = true)
    long countUnreconciled();

    /** What is still owed in these states: the amount due less what has already gone. */
    @Query(value = "select coalesce(sum(amount_due - amount_paid), 0) from courier_payable where state in (:states)",
            nativeQuery = true)
    java.math.BigDecimal sumDue(@Param("states") List<String> states);

    @Query(value = "select coalesce(sum(amount_paid), 0) from courier_payable", nativeQuery = true)
    java.math.BigDecimal sumPaid();
}
