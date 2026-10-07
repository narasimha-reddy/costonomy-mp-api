package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditPayment;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CreditPaymentRepository extends JpaRepository<CreditPayment, Long> {

    Optional<CreditPayment> findByIdempotencyKey(String idempotencyKey);

    /** A receipt's payments, one per invoice. */
    List<CreditPayment> findByCreditRepaymentIdOrderByIdAsc(Long creditRepaymentId);

    List<CreditPayment> findByCreditInvoiceIdOrderByPaidAtAsc(Long creditInvoiceId);

    List<CreditPayment> findByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);

    /**
     * What an invoice was paid from one source, read as the latest committed state and held. A plain read inside a
     * transaction that has already read something uses its older snapshot and would miss a payment another transaction
     * committed while this one waited for the invoice lock; the caller holds the invoice, so no more can arrive.
     */
    @Query(value = "select coalesce(sum(amount), 0) from credit_payment where credit_invoice_id = :invoiceId "
            + "and source = :source for update", nativeQuery = true)
    BigDecimal lockedSumByInvoiceAndSource(@Param("invoiceId") Long invoiceId, @Param("source") String source);

    List<CreditPayment> findByCreditInvoiceIdInOrderByIdAsc(java.util.Collection<Long> creditInvoiceIds);

    List<CreditPayment> findByCreditInvoiceIdOrderByPaidAtDescIdDesc(Long creditInvoiceId);

    /**
     * A supplier store's payments, newest first (id breaks a tie, so paging is stable), between two instants
     * ({@code from} inclusive, {@code to} exclusive; either may be null) and optionally of one source.
     */
    @Query(value = """
            select p from CreditPayment p, CreditAgreement a
             where a.id = p.creditAgreementId and a.supplierStoreId = :storeId
               and (:source is null or p.source = :source)
               and p.paidAt >= :from and p.paidAt < :to
             order by p.paidAt desc, p.id desc
            """,
            countQuery = """
            select count(p) from CreditPayment p, CreditAgreement a
             where a.id = p.creditAgreementId and a.supplierStoreId = :storeId
               and (:source is null or p.source = :source)
               and p.paidAt >= :from and p.paidAt < :to
            """)
    Page<CreditPayment> pageForStore(@Param("storeId") Long storeId, @Param("source") CreditPaymentSource source,
                                     @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** One agreement's payments, newest first, id breaking ties. */
    Page<CreditPayment> findByCreditAgreementIdOrderByPaidAtDescIdDesc(Long creditAgreementId, Pageable pageable);

    /** What a store collected between two instants ({@code from} inclusive, {@code to} exclusive), whatever the source; a payment the supplier reversed is not collected (D-169). */
    @Query("""
            select coalesce(sum(p.amount), 0) from CreditPayment p, CreditAgreement a
             where a.id = p.creditAgreementId and a.supplierStoreId = :storeId
               and p.paidAt >= :from and p.paidAt < :to
               and not exists (select 1 from CreditPaymentReversal r where r.creditPaymentId = p.id)
            """)
    BigDecimal sumForStoreBetween(@Param("storeId") Long storeId, @Param("from") Instant from,
                                  @Param("to") Instant to);

    /**
     * Payments of this store that carry {@code reference} and were made since {@code since}, newest first (D-164).
     * Case-insensitive through the column collation. A payment that belongs to a receipt counts only while the
     * receipt is completed, and a payment with a reversal row never counts, so a reversed one stops blocking its reference.
     */
    @Query("""
            select p from CreditPayment p
             where p.reference = :reference and p.paidAt >= :since
               and p.creditAgreementId in (select a.id from CreditAgreement a where a.supplierStoreId = :storeId)
               and not exists (select 1 from CreditPaymentReversal v where v.creditPaymentId = p.id)
               and (p.creditRepaymentId is null
                    or exists (select 1 from CreditRepayment r
                                where r.id = p.creditRepaymentId
                                  and r.status = com.costonomy.mp.credit.domain.CreditRepaymentStatus.COMPLETED))
             order by p.id desc
            """)
    List<CreditPayment> findRecentWithReference(@Param("storeId") Long storeId, @Param("reference") String reference,
                                                @Param("since") Instant since, Pageable page);
}
