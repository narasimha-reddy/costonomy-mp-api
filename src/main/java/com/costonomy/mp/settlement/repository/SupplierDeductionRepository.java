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

    /**
     * A funded delivery on a credit order waits for no settlement of its own: credit is collected by the supplier, so
     * the order is never paid out. It comes out of the store's next settlement instead.
     */
    @Query(value = """
            select d.* from supplier_deduction d
              join supplier_order o on o.id = d.supplier_order_id
             where d.status = 'PENDING' and d.kind = 'DELIVERY_FUNDING'
               and d.supplier_store_id = :storeId and o.payment_method = 'CREDIT'
            """, nativeQuery = true)
    List<SupplierDeduction> findPendingCreditDeliveryFunding(@Param("storeId") Long storeId);

    List<SupplierDeduction> findByStatusAndSupplierOrderIdIn(String status, Collection<Long> orderIds);

    /**
     * Everything already taken, or waiting to be taken, from this order's payout for refunds. A funded delivery is
     * not a refund and does not use up what the restaurant may be refunded.
     */
    @Query("select coalesce(sum(d.amount), 0) from SupplierDeduction d "
            + "where d.supplierOrderId = :orderId and d.kind = 'DISPUTE_REFUND'")
    BigDecimal sumForOrder(@Param("orderId") Long supplierOrderId);
}
