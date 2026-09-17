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

    /** Sent but unanswered past their expiry. Swept by the expiry job. */
    @Query("""
            select i from Intent i
            where i.status = 'OPEN'
              and i.sentAt < :cutoff
            """)
    List<Intent> findStaleOpen(@Param("cutoff") Instant cutoff);

    /** Answered, unordered, and out of time. */
    @Query("""
            select i from Intent i
            where i.status = 'RESPONSES_RECEIVED'
              and i.orderCreationDeadline < :now
            """)
    List<Intent> findPastOrderWindow(@Param("now") Instant now);
}
