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
