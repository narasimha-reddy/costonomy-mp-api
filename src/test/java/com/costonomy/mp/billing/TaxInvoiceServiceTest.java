package com.costonomy.mp.billing;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.billing.domain.TaxInvoice;
import com.costonomy.mp.billing.repository.CreditNoteItemRepository;
import com.costonomy.mp.billing.repository.CreditNoteRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceItemRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceRepository;
import com.costonomy.mp.billing.service.BillingDirectory;
import com.costonomy.mp.billing.service.BillingDirectory.BuyerParty;
import com.costonomy.mp.billing.service.BillingDirectory.Line;
import com.costonomy.mp.billing.service.BillingDirectory.OrderFacts;
import com.costonomy.mp.billing.service.BillingDirectory.SupplierParty;
import com.costonomy.mp.billing.service.BillingFeature;
import com.costonomy.mp.billing.service.CreditNoteIssuer;
import com.costonomy.mp.billing.service.TaxInvoiceService;
import com.costonomy.mp.billing.service.TaxInvoiceStore;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The directory returns real facts, never nulls that a default could paper over: the old version of this test
 * passed only because a mocked JdbcTemplate returned null and the service fell back to a made-up supplier.
 */
@ExtendWith(MockitoExtension.class)
class TaxInvoiceServiceTest {

    @Mock private BillingDirectory directory;
    @Mock private TaxInvoiceRepository invoices;
    @Mock private TaxInvoiceItemRepository invoiceItems;
    @Mock private CreditNoteRepository creditNotes;
    @Mock private CreditNoteItemRepository creditNoteItems;
    @Mock private TaxInvoiceStore store;
    @Mock private CreditNoteIssuer creditNoteIssuer;
    @Mock private AccessControlService accessControl;

    private TaxInvoiceService service(boolean enabled) {
        return new TaxInvoiceService(new BillingFeature(enabled), directory, invoices, invoiceItems, creditNotes,
                creditNoteItems, store, creditNoteIssuer, accessControl);
    }

    private TaxInvoiceService service;

    @BeforeEach
    void setUp() {
        service = service(true);
    }

    private static OrderFacts order(String status) {
        return new OrderFacts(7L, status, "SO-7", 1L, 2L, BigDecimal.ZERO, new BigDecimal("1050"),
                BigDecimal.ZERO, null, new BigDecimal("1050"));
    }

    private static Line line(String hsn) {
        return new Line(41L, "Fresh Milk", hsn, new BigDecimal("10"), null, "KG", new BigDecimal("100"),
                new BigDecimal("5"), new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("1050"),
                null, null, null);
    }

    private void facts(String status, String supplierGstin, String hsn) {
        when(directory.order(7L)).thenReturn(Optional.of(order(status)));
        when(invoices.findBySupplierOrderId(7L)).thenReturn(Optional.empty());
        lenient(supplierGstin, hsn);
    }

    private void lenient(String supplierGstin, String hsn) {
        when(directory.supplier(1L)).thenReturn(Optional.of(
                new SupplierParty(3L, "Fresh Dairy LLP", supplierGstin, "Kondapur, Hyderabad", "Telangana")));
        when(directory.buyer(2L)).thenReturn(Optional.of(
                new BuyerParty(4L, "Curry Leaf", null, "Hitech City, Hyderabad", "Telangana")));
        when(directory.lines(7L)).thenReturn(List.of(line(hsn)));
    }

    private static TaxInvoice existing() {
        var invoice = new TaxInvoice();
        invoice.setId(99L);
        invoice.setInvoiceNumber("INV/2627/000001");
        invoice.setSupplierOrderId(7L);
        invoice.setSupplierName("Fresh Dairy LLP");
        invoice.setBuyerName("Curry Leaf");
        invoice.setIssuedAt(Instant.parse("2026-10-05T06:00:00Z"));
        invoice.setTaxableAmount(new BigDecimal("1000"));
        invoice.setCgstAmount(new BigDecimal("25"));
        invoice.setSgstAmount(new BigDecimal("25"));
        invoice.setIgstAmount(BigDecimal.ZERO);
        invoice.setDeliveryFee(BigDecimal.ZERO);
        invoice.setTotalAmount(new BigDecimal("1050"));
        return invoice;
    }

    @Test
    @DisplayName("with the feature off every route is a 404 and nothing is read or written")
    void featureOffIsNotFound() {
        var off = service(false);

        assertThatThrownBy(() -> off.generateOrGetInvoice(1L, 7L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.getInvoiceForOrder(1L, 7L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.getCreditNotesForOrder(1L, 7L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.generateTallyXml(1L, 7L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.generateGstr1Csv(1L, 7L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(directory, invoices, store, accessControl);
    }

    @Test
    @DisplayName("generation needs access to the supplier's store: a buyer is refused as not found and nothing is written")
    void buyerCannotGenerate() {
        when(directory.order(7L)).thenReturn(Optional.of(order("READY_FOR_PICKUP")));
        doThrow(new NotFoundException("SupplierOrder", 7L)).when(accessControl)
                .requireScoped(5L, Permissions.ORDER_VIEW, ScopeType.SUPPLIER_STORE, 1L, "SupplierOrder");

        assertThatThrownBy(() -> service.generateOrGetInvoice(5L, 7L)).isInstanceOf(NotFoundException.class);

        verify(store, never()).insert(any());
        verify(invoices, never()).findBySupplierOrderId(any());
    }

    @Test
    @DisplayName("an order that is not yet ready is refused with 422 and writes nothing")
    void notReadyIsRefused() {
        when(directory.order(7L)).thenReturn(Optional.of(order("CONFIRMED")));
        when(invoices.findBySupplierOrderId(7L)).thenReturn(Optional.empty());

        for (String status : new String[] {"DRAFT", "CONFIRMED", "PREPARING", "CANCELLED"}) {
            when(directory.order(7L)).thenReturn(Optional.of(order(status)));
            assertThatThrownBy(() -> service.generateOrGetInvoice(1L, 7L))
                    .describedAs(status)
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.TAX_INVOICE_NOT_ALLOWED));
        }
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("a supplier with no GSTIN gets a 422 naming exactly that, and nothing is written")
    void missingGstinIsRefused() {
        facts("READY_FOR_PICKUP", null, "0401");

        assertThatThrownBy(() -> service.generateOrGetInvoice(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.TAX_INVOICE_DATA_MISSING);
                    assertThat(e.details()).containsEntry("missing", List.of("supplier.gstin"));
                });
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("a line with no HSN code is named, never given a default one")
    void missingHsnIsRefused() {
        facts("READY_FOR_PICKUP", "36AABCU9603R1ZX", null);

        assertThatThrownBy(() -> service.generateOrGetInvoice(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.details()).containsEntry("missing", List.of("line[41 Fresh Milk].hsnCode")));
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("an invoice that already exists is returned without checking anything else or inserting again")
    void existingInvoiceIsReturned() {
        when(directory.order(7L)).thenReturn(Optional.of(order("COMPLETED")));
        when(invoices.findBySupplierOrderId(7L)).thenReturn(Optional.of(existing()));
        when(invoiceItems.findByTaxInvoiceIdOrderByIdAsc(99L)).thenReturn(List.of());

        var response = service.generateOrGetInvoice(1L, 7L);

        assertThat(response.invoiceNumber()).isEqualTo("INV/2627/000001");
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("losing the insert race returns the winner's invoice instead of an error")
    void lostRaceReturnsTheWinner() {
        facts("READY_FOR_PICKUP", "36AABCU9603R1ZX", "0401");
        // First read: nothing there yet; after the duplicate-key failure: the winner's row.
        when(invoices.findBySupplierOrderId(7L)).thenReturn(Optional.empty(), Optional.of(existing()));
        when(store.insert(any())).thenThrow(new DuplicateKeyException("uk_tax_invoice_order"));
        when(invoices.findById(99L)).thenReturn(Optional.of(existing()));
        when(invoiceItems.findByTaxInvoiceIdOrderByIdAsc(99L)).thenReturn(List.of());

        var response = service.generateOrGetInvoice(1L, 7L);

        assertThat(response.id()).isEqualTo(99L);
    }
}
