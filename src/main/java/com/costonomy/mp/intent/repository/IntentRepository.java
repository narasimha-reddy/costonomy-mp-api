package com.costonomy.mp.intent.repository;

import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IntentRepository extends JpaRepository<Intent, Long> {

    /**
     * The intent, locked. Taken first by anything that decides what an intent turns into (an order today), so two such
     * decisions for one intent take turns instead of racing: the second then sees the first's committed result, which
     * a plain read under REPEATABLE READ might not (D-135).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Intent i where i.id = :id")
    Optional<Intent> lockById(@Param("id") Long id);

    List<Intent> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    /**
     * The basket for one supplier.
     *
     * <p>At most one draft per outlet and store, which is what makes "add this
     * pack" mean "add it to the request I am building for this supplier" rather
     * than "start a third one".
     */
    Optional<Intent> findByOutletIdAndSupplierStoreIdAndStatus(
            Long outletId, Long supplierStoreId, IntentStatus status);

    List<Intent> findByOutletIdAndStatus(Long outletId, IntentStatus status);

    /**
     * The id only, so nothing is loaded into the persistence context before the row is locked (a loaded copy would
     * hide what the lock then reads, D-137).
     */
    @Query("select i.id from Intent i where i.outletId = :outletId and i.supplierStoreId = :storeId and i.status = 'DRAFT'")
    Optional<Long> findDraftId(@Param("outletId") Long outletId, @Param("storeId") Long storeId);

    @Query("select i.id from Intent i where i.outletId = :outletId and i.status = 'DRAFT' order by i.id")
    List<Long> findDraftIds(@Param("outletId") Long outletId);

    /** Drafts with at least one line; an emptied draft is not a basket card (D-137). */
    @Query("""
            select i from Intent i
            where i.outletId = :outletId and i.status = 'DRAFT'
              and exists (select 1 from IntentItem l where l.intentId = i.id)
            """)
    List<Intent> findFilledDrafts(@Param("outletId") Long outletId);

    /**
     * Requests a supplier can still answer, newest first.
     *
     * <p>Drafts are excluded by the status filter, not by chance: a basket the
     * restaurant is still filling is nobody else's business.
     */
    @Query("""
            select i from Intent i
            where i.supplierStoreId = :storeId
              and i.status in :statuses
            order by i.createdAt desc
            """)
    List<Intent> findForStore(@Param("storeId") Long storeId,
                              @Param("statuses") List<IntentStatus> statuses);

    /**
     * Sent, unanswered, and past the deadline their store promised.
     *
     * <p>Reads the stored deadline rather than measuring back from a global
     * window: each request carries the window that was in force when it was sent,
     * so a store widening its SLA cannot revive requests that already lapsed.
     */
    @Query("""
            select i from Intent i
            where i.status = 'OPEN'
              and i.responseDeadline < :now
            """)
    List<Intent> findStaleOpen(@Param("now") Instant now);

    /** Answered, unordered, and out of time. */
    @Query("""
            select i from Intent i
            where i.status = 'RESPONSES_RECEIVED'
              and i.orderCreationDeadline < :now
            """)
    List<Intent> findPastOrderWindow(@Param("now") Instant now);
}
