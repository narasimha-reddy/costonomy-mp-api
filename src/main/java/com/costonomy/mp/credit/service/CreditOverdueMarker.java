package com.costonomy.mp.credit.service;

import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Marks one invoice overdue in its own transaction (D-148), so a failure — a lost optimistic lock against a
 * concurrent repayment, say — skips that invoice only and the rest of the sweep still commits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditOverdueMarker {

    private final CreditInvoiceRepository invoices;
    private final OutboxService outbox;

    /**
     * @return true if this call marked the invoice, false if it was no longer due for it (paid, already
     *         overdue, or its grace has not run out)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markOverdue(Long invoiceId, LocalDate today) {
        CreditInvoice invoice = invoices.findById(invoiceId).orElse(null);
        // Re-checked on the fresh row: a repayment may have settled it since the sweep listed it.
        if (invoice == null
                || (invoice.getStatus() != CreditInvoiceStatus.ISSUED
                && invoice.getStatus() != CreditInvoiceStatus.PARTIALLY_PAID)
                || !invoice.getOverdueAfter().isBefore(today)) {
            return false;
        }

        invoice.setStatus(CreditInvoiceStatus.OVERDUE);
        invoice.setMarkedOverdueAt(Instant.now());
        invoices.save(invoice);

        outbox.publish(CreditEvents.OVERDUE, "CREDIT_INVOICE", invoice.getId(),
                Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                        "outletId", invoice.getOutletId(),
                        "outstanding", invoice.outstanding().toPlainString(),
                        "dueDate", invoice.getDueDate().toString()),
                null);

        log.info("Invoice {} is overdue — {} outstanding since {}",
                invoice.getInvoiceNumber(), invoice.outstanding(), invoice.getDueDate());
        return true;
    }
}
