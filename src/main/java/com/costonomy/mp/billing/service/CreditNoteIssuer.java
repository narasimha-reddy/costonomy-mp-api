package com.costonomy.mp.billing.service;

import com.costonomy.mp.billing.repository.CreditNoteRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceRepository;
import com.costonomy.mp.procurement.domain.Pricing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * Issues the credit note for a doorstep rejection (Section 34 CGST Act), after the check-in that caused it has
 * committed. Billing can never fail a check-in: the refund and the adjustment row are recorded by receiving, and
 * this runs afterwards from the {@code ReceivingCompleted} event, or when the order's invoice is generated.
 *
 * <p>Not transactional itself: the insert is {@link CreditNoteStore}'s, in its own transaction. A credit note that
 * cannot be issued yet (the supplier's GSTIN or a line's HSN is missing) is logged and left for the catch-up when
 * the invoice is generated, which needs the same details. Replays are harmless: one note per order and reason.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditNoteIssuer {

    private static final String REASON = "DOORSTEP_REJECTION";

    private final BillingDirectory directory;
    private final CreditNoteRepository creditNotes;
    private final TaxInvoiceRepository invoices;
    private final CreditNoteStore store;

    /** @return the new credit note's id, or null when none was needed, it already exists, or it cannot be issued yet */
    public Long issueForDoorstep(Long orderId) {
        if (creditNotes.existsBySupplierOrderIdAndReasonCode(orderId, REASON)) {
            return null;
        }
        var order = directory.order(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        var rejected = directory.lines(orderId).stream()
                .filter(l -> l.rejectedQuantity() != null && l.rejectedQuantity().signum() > 0)
                .toList();
        if (rejected.isEmpty()) {
            return null;
        }

        var supplier = directory.supplier(order.supplierStoreId()).orElse(null);
        var buyer = directory.buyer(order.outletId()).orElse(null);
        var missing = InvoiceRequirements.missing(supplier, buyer, rejected);
        if (!missing.isEmpty()) {
            log.warn("Credit note for order {} is waiting for: {}", orderId, String.join(", ", missing));
            return null;
        }
        var parties = InvoiceRequirements.parties(supplier, buyer);

        var lines = new ArrayList<CreditNoteStore.Line>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal storedRefund = BigDecimal.ZERO;
        for (var line : rejected) {
            var part = Pricing.rejection(line.lineItemValue(), line.lineGst(), line.lineTotal(),
                    line.suppliedQuantity(), line.rejectedQuantity(), line.unitPrice(), line.gstRate());
            var split = InvoiceCalculator.split(part.gst(), parties.interState());
            lines.add(new CreditNoteStore.Line(line.id(), line.productName(), line.hsnCode(),
                    line.rejectedQuantity(), line.unit(), line.unitPrice(), line.gstRate(), part.taxable(),
                    split[0], split[1], split[2], part.total(), line.rejectionReason()));
            total = total.add(part.total());
            if (line.refundAmount() != null) {
                storedRefund = storedRefund.add(line.refundAmount());
            }
        }
        // The note must say exactly what receiving refunded.
        if (Pricing.money(total).compareTo(Pricing.money(storedRefund)) != 0) {
            throw new IllegalStateException("Credit note for order %d totals %s but receiving refunded %s"
                    .formatted(orderId, total.toPlainString(), storedRefund.toPlainString()));
        }

        var invoice = invoices.findBySupplierOrderId(orderId).orElse(null);
        try {
            return store.insert(new CreditNoteStore.Draft(order, supplier, buyer, parties, invoice, lines));
        } catch (DataIntegrityViolationException duplicate) {
            // Another run issued it first (uk_credit_note_order_reason): nothing more to do.
            log.info("Credit note for order {} was issued by another run", orderId);
            return null;
        }
    }
}
