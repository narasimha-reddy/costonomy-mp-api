package com.costonomy.mp.procurement.repository;

import com.costonomy.mp.procurement.domain.OrderAdjustment;
import com.costonomy.mp.procurement.domain.OrderAdjustmentReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface OrderAdjustmentRepository extends JpaRepository<OrderAdjustment, Long> {

    List<OrderAdjustment> findBySupplierOrderIdOrderByIdAsc(Long supplierOrderId);

    Optional<OrderAdjustment> findBySupplierOrderIdAndReason(Long supplierOrderId, OrderAdjustmentReason reason);

    @Query("select coalesce(sum(a.amount), 0) from OrderAdjustment a where a.supplierOrderId = :orderId")
    BigDecimal sumForOrder(@Param("orderId") Long supplierOrderId);

    /** The row, locked for the rest of the transaction. Taken after the order's own lock (D-129 lock order). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OrderAdjustment a where a.id = :id")
    Optional<OrderAdjustment> lockById(@Param("id") Long id);

    @Query("select a.id from OrderAdjustment a where a.status = com.costonomy.mp.procurement.domain.OrderAdjustmentStatus.PENDING_CAPTURE order by a.createdAt")
    List<Long> pendingIds(Pageable page);
}
