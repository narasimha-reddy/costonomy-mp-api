package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CreditInvoiceRepository extends JpaRepository<CreditInvoice, Long> {

    /**
     * One invoice, held until the transaction ends. Whoever is about to change its paid amount takes this first, so
     * two payments against the same invoice (a supplier recording one while a restaurant repays from its wallet)
     * cannot both be computed from the same outstanding amount (D-123).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CreditInvoice i where i.id = :id")
    Optional<CreditInvoice> lockById(@Param("id") Long id);

    /**
     * Every open invoice of an agreement, in ascending id order, held until the transaction ends. Ascending id is the
     * one order every taker uses, so two repayments overlapping on several invoices cannot lock them crosswise.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from CreditInvoice i
             where i.creditAgreementId = :agreementId and i.status not in :settled
             order by i.id
            """)
    List<CreditInvoice> lockOpenOfAgreement(@Param("agreementId") Long agreementId,
                                            @Param("settled") List<CreditInvoiceStatus> settled);

    /** As {@link #lockOpenOfAgreement}, restricted to the given ids: one that is not this agreement's, or not open, is simply absent. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from CreditInvoice i
             where i.creditAgreementId = :agreementId and i.status not in :settled and i.id in :ids
             order by i.id
            """)
    List<CreditInvoice> lockOpenOfAgreementWithIds(@Param("agreementId") Long agreementId,
                                                   @Param("settled") List<CreditInvoiceStatus> settled,
                                                   @Param("ids") List<Long> ids);

    Optional<CreditInvoice> findBySupplierOrderId(Long supplierOrderId);

    List<CreditInvoice> findByCreditAgreementIdOrderByDueDateAsc(Long creditAgreementId);

    /**
     * Invoices whose grace period has run out and which nobody has marked overdue.
     *
     * <p>Both halves matter: without the status filter the sweep would re-mark the
     * same invoices every minute and emit a CreditOverdue notification each time.
     */
    @Query("""
            select i from CreditInvoice i
             where i.status in :openStatuses
               and i.overdueAfter < :today
            """)
    List<CreditInvoice> findNewlyOverdue(@Param("openStatuses") List<CreditInvoiceStatus> openStatuses,
                                         @Param("today") LocalDate today);

    /** Does the outlet have an invoice in this status? Matches ix_credit_invoice_outlet_status (outlet_id, status). */
    boolean existsByOutletIdAndStatus(Long outletId, CreditInvoiceStatus status);

    /**
     * Is any open invoice of the outlet due on or before {@code latestDue}? The caller passes only the open,
     * not-yet-overdue statuses, so the same index (outlet_id, status) narrows it before the date is looked at.
     */
    boolean existsByOutletIdAndStatusInAndDueDateLessThanEqual(Long outletId, List<CreditInvoiceStatus> statuses,
                                                              LocalDate latestDue);
}
