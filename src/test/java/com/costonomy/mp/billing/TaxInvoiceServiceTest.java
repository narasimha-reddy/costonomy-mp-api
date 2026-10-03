package com.costonomy.mp.billing;

import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.billing.domain.CreditNote;
import com.costonomy.mp.billing.domain.TaxInvoice;
import com.costonomy.mp.billing.repository.CreditNoteItemRepository;
import com.costonomy.mp.billing.repository.CreditNoteRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceItemRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceRepository;
import com.costonomy.mp.billing.service.TaxInvoiceService;
import com.costonomy.mp.catalog.domain.CanonicalProduct;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxInvoiceServiceTest {

    @Mock
    private TaxInvoiceRepository invoices;

    @Mock
    private TaxInvoiceItemRepository invoiceItems;

    @Mock
    private CreditNoteRepository creditNotes;

    @Mock
    private CreditNoteItemRepository creditNoteItems;

    @Mock
    private SupplierOrderRepository orders;

    @Mock
    private SupplierOrderItemRepository orderItems;

    @Mock
    private CanonicalProductRepository products;

    @Mock
    private AccessControlService accessControl;

    @Mock
    private JdbcTemplate jdbc;

    private TaxInvoiceService service;

    @BeforeEach
    void setUp() {
        service = new TaxInvoiceService(
                invoices, invoiceItems, creditNotes, creditNoteItems,
                orders, orderItems, products, accessControl, jdbc);
    }

    @Test
    @DisplayName("generateOrGetInvoice computes intra-state CGST and SGST 50/50 breakdown")
    void generateIntraStateInvoice() {
        SupplierOrder order = new SupplierOrder();
        order.setId(101L);
        order.setOrderNumber("ORD-101");
        order.setSupplierStoreId(10L);
        order.setOutletId(20L);
        order.setDeliveryFee(new BigDecimal("50.00"));

        SupplierOrderItem item = new SupplierOrderItem();
        item.setId(201L);
        item.setSupplierOrderId(101L);
        item.setCanonicalProductId(301L);
        item.setAcceptedQuantity(new BigDecimal("10.00"));
        item.setUnit("KG");
        item.setUnitPriceSnapshot(new BigDecimal("100.00"));
        item.setGstRateSnapshot(new BigDecimal("18.00"));
        item.setHsnCode("0406");

        when(invoices.findBySupplierOrderId(101L)).thenReturn(Optional.empty());
        when(orders.findById(101L)).thenReturn(Optional.of(order));
        when(orderItems.findBySupplierOrderId(101L)).thenReturn(List.of(item));

        CanonicalProduct product = new CanonicalProduct();
        product.setName("Fresh Malai Paneer");
        when(products.findById(301L)).thenReturn(Optional.of(product));

        when(invoices.save(any(TaxInvoice.class))).thenAnswer(inv -> {
            TaxInvoice t = inv.getArgument(0);
            t.setId(501L);
            return t;
        });

        var res = service.generateOrGetInvoice(101L);

        assertThat(res.id()).isEqualTo(501L);
        assertThat(res.invoiceNumber()).startsWith("INV-");
        // Item value: 10 * 100 = 1000. GST: 18% = 180. Intra-state split: CGST 90, SGST 90
        assertThat(res.taxableAmount()).isEqualByComparingTo("1000.00");
        assertThat(res.cgstAmount()).isEqualByComparingTo("90.00");
        assertThat(res.sgstAmount()).isEqualByComparingTo("90.00");
        assertThat(res.igstAmount()).isEqualByComparingTo("0.00");
        assertThat(res.deliveryFee()).isEqualByComparingTo("50.00");
        assertThat(res.totalAmount()).isEqualByComparingTo("1230.00");
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).hsnCode()).isEqualTo("0406");
    }

    @Test
    @DisplayName("generateCreditNoteForRejection computes Section 34 CGST Act credit note for doorstep rejections")
    void generateCreditNoteForDoorstepRejection() {
        SupplierOrder order = new SupplierOrder();
        order.setId(102L);
        order.setOrderNumber("ORD-102");
        order.setSupplierStoreId(10L);
        order.setOutletId(20L);

        SupplierOrderItem item = new SupplierOrderItem();
        item.setId(202L);
        item.setSupplierOrderId(102L);
        item.setCanonicalProductId(302L);
        item.setDoorstepRejectedQty(new BigDecimal("2.00"));
        item.setUnit("KG");
        item.setUnitPriceSnapshot(new BigDecimal("200.00"));
        item.setGstRateSnapshot(new BigDecimal("18.00"));
        item.setHsnCode("0406");
        item.setDoorstepRejectionReason("SPOILED_PACK");

        when(orders.findById(102L)).thenReturn(Optional.of(order));
        when(orderItems.findBySupplierOrderId(102L)).thenReturn(List.of(item));
        when(creditNotes.findBySupplierOrderIdOrderByIdAsc(102L)).thenReturn(List.of());

        CanonicalProduct product = new CanonicalProduct();
        product.setName("Fresh Cheese");
        when(products.findById(302L)).thenReturn(Optional.of(product));

        when(creditNotes.save(any(CreditNote.class))).thenAnswer(inv -> {
            CreditNote cn = inv.getArgument(0);
            cn.setId(601L);
            return cn;
        });

        var res = service.generateCreditNoteForRejection(102L, "DOORSTEP_REJECTION");

        assertThat(res).isNotNull();
        assertThat(res.creditNoteNumber()).startsWith("CN-");
        // Rejected taxable: 2 * 200 = 400. GST: 18% = 72. CGST 36, SGST 36. Total refund = 472.00
        assertThat(res.taxableRefundAmount()).isEqualByComparingTo("400.00");
        assertThat(res.cgstRefundAmount()).isEqualByComparingTo("36.00");
        assertThat(res.sgstRefundAmount()).isEqualByComparingTo("36.00");
        assertThat(res.totalRefundAmount()).isEqualByComparingTo("472.00");
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).rejectedQuantity()).isEqualByComparingTo("2.00");
        assertThat(res.items().get(0).rejectionReason()).isEqualTo("SPOILED_PACK");
    }
}
