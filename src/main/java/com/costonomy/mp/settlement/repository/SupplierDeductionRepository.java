package com.costonomy.mp.settlement.repository;

import com.costonomy.mp.settlement.domain.SupplierDeduction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierDeductionRepository extends JpaRepository<SupplierDeduction, Long> {

    Optional<SupplierDeduction> findByDisputeRefundId(Long disputeRefundId);

    List<SupplierDeduction> findByStatusAndSupplierOrderIdIn(String status, Collection<Long> orderIds);

    /** Everything already taken, or waiting to be taken, from this order's payout. */
    @Query("select coalesce(sum(d.amount), 0) from SupplierDeduction d where d.supplierOrderId = :orderId")
    BigDecimal sumForOrder(@Param("orderId") Long supplierOrderId);
}
