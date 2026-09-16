package com.costonomy.mp.procurement.repository;

import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SupplierOrderRepository extends JpaRepository<SupplierOrder, Long> {

    List<SupplierOrder> findByProcurementId(Long procurementId);

    List<SupplierOrder> findBySupplierStoreIdAndStatusInOrderByAcceptanceDeadlineAsc(
            Long supplierStoreId, Collection<SupplierOrderStatus> statuses);

    List<SupplierOrder> findByOutletIdOrderByCreatedAtDesc(Long outletId);

    /**
     * A store's orders in a window, newest first.
     *
     * <p>Always filtered by status, never by absence of one: a supplier must not
     * see a DRAFT order (guardrail 16, D-020), so "all statuses" is a list the
     * service builds rather than a query that omits the clause.
     *
     * <p>Bounded by {@code createdAt} because an unbounded history is a table
     * scan that grows with the marketplace, and nobody browsing orders wants one.
     */
    List<SupplierOrder> findBySupplierStoreIdAndStatusInAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long supplierStoreId,
            Collection<SupplierOrderStatus> statuses,
            Instant from,
            Instant to);

    /**
     * Orders whose response window has closed. Read by the timeout job (Phase 8).
     *
     * <p>Selects candidates only — it does not expire them. Each is transitioned
     * individually under optimistic locking, because a supplier may be accepting
     * at the same instant and doc 03 §5 requires exactly one of the two to win.
     */
    @Query("""
            select o from SupplierOrder o
            where o.status = com.costonomy.mp.procurement.domain.SupplierOrderStatus.PENDING_ACCEPTANCE
              and o.acceptanceDeadline < :now
            order by o.acceptanceDeadline asc
            """)
    List<SupplierOrder> findExpiredCandidates(@Param("now") Instant now);
}
