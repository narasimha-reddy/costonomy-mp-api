package com.costonomy.mp.billing.service;

import com.costonomy.mp.billing.domain.TaxInvoice;
import com.costonomy.mp.billing.domain.TaxInvoiceItem;
import com.costonomy.mp.billing.repository.TaxInvoiceItemRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts a tax invoice in a transaction of its own (D-021). A separate bean so the proxy applies.
 *
 * <p>It allocates the number and inserts in the same transaction, so an insert that loses the race to another
 * request for the same order throws {@code DataIntegrityViolationException} on {@code uk_tax_invoice_order} and
 * rolls the number back with it. It never catches that: the caller re-reads the winner's invoice.
 */
@Service
@RequiredArgsConstructor
public class TaxInvoiceStore {

    private final TaxInvoiceRepository invoices;
    private final TaxInvoiceItemRepository invoiceItems;
    private final DocumentSequenceAllocator sequences;
    private final JdbcTemplate jdbc;

    /** Everything an invoice records, resolved and validated before any number is taken. */
    public record Draft(BillingDirectory.OrderFacts order, BillingDirectory.SupplierParty supplier,
                        BillingDirectory.BuyerParty buyer, InvoiceRequirements.Parties parties,
                        InvoiceCalculator.Result figures) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insert(Draft draft) {
        Instant now = Instant.now();
        int fiscalYear = FiscalYear.startYear(now);
        String gstin = draft.supplier().gstin().trim();
        long sequence = sequences.next(gstin, fiscalYear, "TAX_INVOICE");

        var order = draft.order();
        var supplier = draft.supplier();
        var buyer = draft.buyer();
        var parties = draft.parties();
        var figures = draft.figures();

        var invoice = new TaxInvoice();
        invoice.setInvoiceNumber(FiscalYear.number("INV", fiscalYear, sequence));
        invoice.setFiscalYear(fiscalYear);
        invoice.setSequenceValue(sequence);
        invoice.setSupplierOrderId(order.id());
        invoice.setSupplierStoreId(order.supplierStoreId());
        invoice.setSupplierOrganizationId(supplier.organizationId());
        invoice.setOutletId(order.outletId());
        invoice.setRestaurantId(buyer.restaurantId());
        invoice.setSupplierName(supplier.legalName().trim());
        invoice.setSupplierGstin(gstin);
        invoice.setSupplierAddress(supplier.address());
        invoice.setSupplierStateCode(parties.supplierState().code());
        invoice.setBuyerName(buyer.name().trim());
        invoice.setBuyerGstin(buyer.gstin() == null || buyer.gstin().isBlank() ? null : buyer.gstin().trim());
        invoice.setBuyerAddress(buyer.address());
        invoice.setBuyerStateCode(parties.placeOfSupply().code());
        invoice.setPlaceOfSupply(parties.placeOfSupply().placeOfSupply());
        invoice.setInterState(parties.interState());
        invoice.setTaxableAmount(figures.taxable());
        invoice.setCgstAmount(figures.cgst());
        invoice.setSgstAmount(figures.sgst());
        invoice.setIgstAmount(figures.igst());
        invoice.setDeliveryFee(figures.deliveryFee());
        invoice.setTotalAmount(figures.total());
        invoice.setStatus("ISSUED");
        invoice.setIssuedAt(now);
        invoices.saveAndFlush(invoice);

        List<TaxInvoiceItem> items = new ArrayList<>();
        for (var line : figures.lines()) {
            var item = new TaxInvoiceItem();
            item.setTaxInvoiceId(invoice.getId());
            item.setSupplierOrderItemId(line.orderItemId());
            item.setProductName(line.productName());
            item.setHsnCode(line.hsnCode().trim());
            item.setQuantity(line.quantity());
            item.setUnit(line.unit());
            item.setUnitPrice(line.unitPrice());
            item.setTaxableValue(line.taxable());
            item.setGstRate(line.gstRate());
            item.setCgstAmount(line.cgst());
            item.setSgstAmount(line.sgst());
            item.setIgstAmount(line.igst());
            item.setTotalAmount(line.total());
            items.add(item);
        }
        invoiceItems.saveAll(items);
        invoiceItems.flush();

        // A credit note issued before this invoice existed was left unlinked; it belongs to this invoice now.
        jdbc.update("""
                update credit_note set tax_invoice_id = ?, tax_invoice_number = ?
                 where supplier_order_id = ? and tax_invoice_id is null
                """, invoice.getId(), invoice.getInvoiceNumber(), order.id());
        return invoice.getId();
    }
}
