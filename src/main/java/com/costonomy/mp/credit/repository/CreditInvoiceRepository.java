package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CreditInvoiceRepository extends JpaRepository<CreditInvoice, Long> {

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
}
