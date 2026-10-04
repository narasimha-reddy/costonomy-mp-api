package com.costonomy.mp.billing.service;

import com.costonomy.mp.billing.domain.CreditNote;
import com.costonomy.mp.billing.domain.CreditNoteItem;
import com.costonomy.mp.billing.domain.TaxInvoice;
import com.costonomy.mp.billing.repository.CreditNoteItemRepository;
import com.costonomy.mp.billing.repository.CreditNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts a credit note in a transaction of its own, for the same reason as {@link TaxInvoiceStore}: the caller is
 * an event listener, and a failure here must roll back only this note and its number, never the batch the event
 * arrived in. A duplicate for the same order and reason throws and is the caller's to ignore.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteStore {

    private final CreditNoteRepository notes;
    private final CreditNoteItemRepository noteItems;
    private final DocumentSequenceAllocator sequences;

    /** One credit note line: the part of an order line that was rejected at the door. */
    public record Line(Long orderItemId, String productName, String hsnCode, BigDecimal quantity, String unit,
                       BigDecimal unitPrice, BigDecimal gstRate, BigDecimal taxable, BigDecimal cgst,
                       BigDecimal sgst, BigDecimal igst, BigDecimal total, String reason) {
    }

    public record Draft(BillingDirectory.OrderFacts order, BillingDirectory.SupplierParty supplier,
                        BillingDirectory.BuyerParty buyer, InvoiceRequirements.Parties parties,
                        TaxInvoice invoice, List<Line> lines) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insert(Draft draft) {
        Instant now = Instant.now();
        int fiscalYear = FiscalYear.startYear(now);
        String gstin = draft.supplier().gstin().trim();
        long sequence = sequences.next(gstin, fiscalYear, "CREDIT_NOTE");

        var order = draft.order();
        var supplier = draft.supplier();
        var buyer = draft.buyer();
        var invoice = draft.invoice();

        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (var line : draft.lines()) {
            taxable = taxable.add(line.taxable());
            cgst = cgst.add(line.cgst());
            sgst = sgst.add(line.sgst());
            igst = igst.add(line.igst());
            total = total.add(line.total());
        }

        var note = new CreditNote();
        note.setCreditNoteNumber(FiscalYear.number("CN", fiscalYear, sequence));
        note.setFiscalYear(fiscalYear);
        note.setSequenceValue(sequence);
        note.setTaxInvoiceId(invoice == null ? null : invoice.getId());
        note.setTaxInvoiceNumber(invoice == null ? null : invoice.getInvoiceNumber());
        note.setSupplierOrderId(order.id());
        note.setSupplierStoreId(order.supplierStoreId());
        note.setSupplierOrganizationId(supplier.organizationId());
        note.setOutletId(order.outletId());
        note.setRestaurantId(buyer.restaurantId());
        note.setSupplierName(supplier.legalName().trim());
        note.setSupplierGstin(gstin);
        note.setBuyerName(buyer.name().trim());
        note.setBuyerGstin(buyer.gstin() == null || buyer.gstin().isBlank() ? null : buyer.gstin().trim());
        note.setReasonCode("DOORSTEP_REJECTION");
        note.setInterState(draft.parties().interState());
        note.setTaxableRefundAmount(taxable);
        note.setCgstRefundAmount(cgst);
        note.setSgstRefundAmount(sgst);
        note.setIgstRefundAmount(igst);
        note.setTotalRefundAmount(total);
        note.setStatus("ISSUED");
        note.setIssuedAt(now);
        notes.saveAndFlush(note);

        List<CreditNoteItem> items = new ArrayList<>();
        for (var line : draft.lines()) {
            var item = new CreditNoteItem();
            item.setCreditNoteId(note.getId());
            item.setSupplierOrderItemId(line.orderItemId());
            item.setProductName(line.productName());
            item.setHsnCode(line.hsnCode().trim());
            item.setRejectedQuantity(line.quantity());
            item.setUnit(line.unit());
            item.setUnitPrice(line.unitPrice());
            item.setTaxableRefund(line.taxable());
            item.setGstRate(line.gstRate());
            item.setCgstRefund(line.cgst());
            item.setSgstRefund(line.sgst());
            item.setIgstRefund(line.igst());
            item.setTotalRefund(line.total());
            item.setRejectionReason(line.reason());
            items.add(item);
        }
        noteItems.saveAll(items);
        noteItems.flush();
        return note.getId();
    }
}
