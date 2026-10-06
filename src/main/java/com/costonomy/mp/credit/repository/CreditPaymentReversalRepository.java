package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPaymentReversal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CreditPaymentReversalRepository extends JpaRepository<CreditPaymentReversal, Long> {

    List<CreditPaymentReversal> findByCreditPaymentIdIn(Collection<Long> paymentIds);

    /** An invoice's reversals in the order they were written, for matching a statement row to its payment. */
    List<CreditPaymentReversal> findByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);

    /**
     * Which of these payments have a reversal, read as the latest committed state and held. A plain read inside a
     * transaction that has already read something would use its older snapshot and miss a reversal another
     * transaction just committed; the caller holds the payments' invoices, so nobody else can be adding one.
     */
    @Query(value = "select credit_payment_id from credit_payment_reversal where credit_payment_id in (:ids) for update",
            nativeQuery = true)
    List<Long> lockReversedAmong(@Param("ids") Collection<Long> paymentIds);
}
