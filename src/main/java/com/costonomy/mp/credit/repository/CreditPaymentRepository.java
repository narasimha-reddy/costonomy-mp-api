package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPayment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CreditPaymentRepository extends JpaRepository<CreditPayment, Long> {

    Optional<CreditPayment> findByIdempotencyKey(String idempotencyKey);

    List<CreditPayment> findByCreditInvoiceIdOrderByPaidAtAsc(Long creditInvoiceId);

    List<CreditPayment> findByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);

    List<CreditPayment> findByCreditInvoiceIdOrderByPaidAtDescIdDesc(Long creditInvoiceId);

    /**
     * Payments of this store that carry {@code reference} and were made since {@code since}, newest first (D-134).
     * Case-insensitive through the column collation. A payment that belongs to a receipt counts only while the
     * receipt is completed, so a reversed one stops blocking its reference.
     */
    @Query("""
            select p from CreditPayment p
             where p.reference = :reference and p.paidAt >= :since
               and p.creditAgreementId in (select a.id from CreditAgreement a where a.supplierStoreId = :storeId)
               and (p.creditRepaymentId is null
                    or exists (select 1 from CreditRepayment r
                                where r.id = p.creditRepaymentId
                                  and r.status = com.costonomy.mp.credit.domain.CreditRepaymentStatus.COMPLETED))
             order by p.id desc
            """)
    List<CreditPayment> findRecentWithReference(@Param("storeId") Long storeId, @Param("reference") String reference,
                                                @Param("since") Instant since, Pageable page);
}
