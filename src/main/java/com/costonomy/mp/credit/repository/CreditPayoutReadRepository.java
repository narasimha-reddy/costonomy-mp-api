package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPayment;
import com.costonomy.mp.credit.domain.CreditRepaymentPayout;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Read-only queries behind the supplier's payouts screen; kept apart from the writer's repository. */
public interface CreditPayoutReadRepository extends Repository<CreditRepaymentPayout, Long> {

    /** One store's payouts, newest first. A null filter is "any". */
    @Query("""
            select p from CreditRepaymentPayout p
             where p.supplierStoreId = :storeId
               and (:status is null or p.status = :status)
               and (:from is null or p.createdAt >= :from)
               and (:to is null or p.createdAt < :to)
             order by p.createdAt desc, p.id desc
            """)
    Page<CreditRepaymentPayout> page(@Param("storeId") Long storeId, @Param("status") String status,
                                     @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    Optional<CreditRepaymentPayout> findByIdAndSupplierStoreId(Long id, Long supplierStoreId);

    /** What the store is waiting to be paid: amount less the stored commission, still PENDING. */
    @Query("""
            select coalesce(sum(p.amount - p.commissionAmount), 0) from CreditRepaymentPayout p
             where p.supplierStoreId = :storeId and p.status = 'PENDING'
            """)
    BigDecimal pendingNet(@Param("storeId") Long storeId);

    /** Net of the payouts a settlement applied within the window. */
    @Query("""
            select coalesce(sum(p.amount - p.commissionAmount), 0) from CreditRepaymentPayout p
             where p.supplierStoreId = :storeId and p.status = 'APPLIED'
               and p.appliedAt >= :from and p.appliedAt < :to
            """)
    BigDecimal appliedNet(@Param("storeId") Long storeId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select pay from CreditPayment pay where pay.creditRepaymentId in :repaymentIds order by pay.id")
    List<CreditPayment> paymentsOf(@Param("repaymentIds") Collection<Long> repaymentIds);
}
