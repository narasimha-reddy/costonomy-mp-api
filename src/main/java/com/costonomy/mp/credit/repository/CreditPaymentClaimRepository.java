package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditClaimStatus;
import com.costonomy.mp.credit.domain.CreditPaymentClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CreditPaymentClaimRepository extends JpaRepository<CreditPaymentClaim, Long> {

    /**
     * One claim, held until the transaction ends. Whoever decides or withdraws it takes this first, then (to confirm)
     * the invoice, always in that order, so two answers to one claim cannot both go through.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditPaymentClaim c where c.id = :id")
    Optional<CreditPaymentClaim> lockById(@Param("id") Long id);

    /** What the restaurant has said it paid on this invoice and the supplier has not yet answered. */
    @Query("""
            select coalesce(sum(c.amount), 0) from CreditPaymentClaim c
             where c.creditInvoiceId = :invoiceId and c.status = :status
            """)
    BigDecimal sumByInvoiceAndStatus(@Param("invoiceId") Long invoiceId, @Param("status") CreditClaimStatus status);

    @Query("""
            select coalesce(sum(c.amount), 0) from CreditPaymentClaim c
             where c.creditAgreementId = :agreementId and c.status = :status
            """)
    BigDecimal sumByAgreementAndStatus(@Param("agreementId") Long agreementId,
                                       @Param("status") CreditClaimStatus status);

    /** Open claim totals for every invoice of an agreement in one query: rows of (invoice id, sum). */
    @Query("""
            select c.creditInvoiceId, sum(c.amount) from CreditPaymentClaim c
             where c.creditAgreementId = :agreementId and c.status = :status
             group by c.creditInvoiceId
            """)
    List<Object[]> sumsByInvoiceForAgreement(@Param("agreementId") Long agreementId,
                                             @Param("status") CreditClaimStatus status);

    /**
     * The ids of an invoice's SUBMITTED claims that nobody holds right now, taken for update. A claim being confirmed
     * or withdrawn holds its own row, and that transaction then wants the invoice the caller already holds, so
     * waiting for it here would be a deadlock (the order is claim, then invoice): such a claim is skipped, and its
     * own transaction finds the invoice settled and refuses with CREDIT_CLAIM_STATE.
     */
    @Query(value = """
            select id from credit_payment_claim
             where credit_invoice_id = :invoiceId and status = 'SUBMITTED' and id <> :exceptId
               for update skip locked
            """, nativeQuery = true)
    List<Long> lockFreeSubmittedIds(@Param("invoiceId") Long invoiceId, @Param("exceptId") Long exceptId);

    /** Close the given claims as superseded (D-130): the invoice they point at is settled. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update CreditPaymentClaim c set c.status = com.costonomy.mp.credit.domain.CreditClaimStatus.SUPERSEDED,
                   c.decisionNote = :note, c.decidedAt = :now
             where c.id in :ids
            """)
    int supersede(@Param("ids") List<Long> ids, @Param("note") String note, @Param("now") java.time.Instant now);

    List<CreditPaymentClaim> findByCreditInvoiceIdOrderByIdDesc(Long creditInvoiceId);

    List<CreditPaymentClaim> findByCreditAgreementIdOrderByIdDesc(Long creditAgreementId);

    List<CreditPaymentClaim> findByCreditAgreementIdAndStatusOrderByIdDesc(Long creditAgreementId,
                                                                           CreditClaimStatus status);

    List<CreditPaymentClaim> findBySupplierStoreIdOrderByIdDesc(Long supplierStoreId);

    List<CreditPaymentClaim> findBySupplierStoreIdAndStatusOrderByIdDesc(Long supplierStoreId,
                                                                         CreditClaimStatus status);
}
