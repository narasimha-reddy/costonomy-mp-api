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

    Optional<SupplierDeduction> findByProviderOrderId(String providerOrderId);

    Optional<SupplierDeduction> findByDeliveryId(Long deliveryId);

    /** Credits waiting for this store's next settlement. */
    @Query(value = "select * from supplier_deduction where kind = 'DELIVERY_FUNDING_CREDIT' and status = 'PENDING' "
            + "and supplier_store_id = :storeId", nativeQuery = true)
    List<SupplierDeduction> findPendingCredits(@Param("storeId") Long storeId);

    /** Stores owed a credit, so a settlement is made for them even with no order to settle. */
    @Query(value = "select distinct supplier_store_id from supplier_deduction "
            + "where kind = 'DELIVERY_FUNDING_CREDIT' and status = 'PENDING'", nativeQuery = true)
    List<Long> storesWithPendingCredits();

    /** What has already been credited back for one charge, pending or applied. */
    @Query(value = "select coalesce(sum(amount), 0) from supplier_deduction where kind = 'DELIVERY_FUNDING_CREDIT' "
            + "and corrects_deduction_id = :deductionId", nativeQuery = true)
    BigDecimal sumCreditsFor(@Param("deductionId") Long deductionId);

    Optional<SupplierDeduction> findByPaymentId(String paymentId);

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

    @Query(value = """
            SELECT * FROM supplier_deduction
            WHERE status IN ('PENDING', 'APPLIED')
              AND kind = 'DELIVERY_FUNDING'
              AND provider_order_id IS NOT NULL
              AND paid_at IS NULL
              AND gateway_refunded_at IS NULL
              AND checkout_opened_at < :staleBefore
              AND checkout_opened_at > :createdAfter
            ORDER BY id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<SupplierDeduction> findStaleGatewayDeductions(
            @Param("staleBefore") java.time.Instant staleBefore,
            @Param("createdAfter") java.time.Instant createdAfter,
            @Param("limit") int limit);

    /**
     * Everything already taken, or waiting to be taken, from this order's payout for refunds. A funded delivery is
     * not a refund and does not use up what the restaurant may be refunded.
     */
    @Query("select coalesce(sum(d.amount), 0) from SupplierDeduction d "
            + "where d.supplierOrderId = :orderId and d.kind = 'DISPUTE_REFUND'")
    BigDecimal sumForOrder(@Param("orderId") Long supplierOrderId);
}
