package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditReminderInvoice;
import com.costonomy.mp.credit.domain.CreditReminderKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CreditReminderInvoiceRepository extends JpaRepository<CreditReminderInvoice, Long> {

    List<CreditReminderInvoice> findByCreditReminderIdOrderByCreditInvoiceIdAsc(Long creditReminderId);

    List<CreditReminderInvoice> findByCreditReminderIdIn(Collection<Long> creditReminderIds);

    /** Invoices of this list that already had this automatic reminder (key {@code KIND:day}). */
    @Query("select r.creditInvoiceId from CreditReminderInvoice r where r.creditInvoiceId in :ids and r.autoKey = :autoKey")
    List<Long> alreadyReminded(@Param("ids") Collection<Long> ids, @Param("autoKey") String autoKey);

    long countByCreditInvoiceIdAndKind(Long creditInvoiceId, CreditReminderKind kind);

    @Query("select max(r.remindDate) from CreditReminderInvoice r where r.creditInvoiceId = :id and r.kind = :kind")
    java.time.LocalDate lastRemindDate(@Param("id") Long id, @Param("kind") CreditReminderKind kind);
}
