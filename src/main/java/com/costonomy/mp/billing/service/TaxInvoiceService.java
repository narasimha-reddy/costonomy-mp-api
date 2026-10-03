package com.costonomy.mp.billing.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.billing.domain.CreditNote;
import com.costonomy.mp.billing.domain.CreditNoteItem;
import com.costonomy.mp.billing.domain.TaxInvoice;
import com.costonomy.mp.billing.domain.TaxInvoiceItem;
import com.costonomy.mp.billing.repository.CreditNoteItemRepository;
import com.costonomy.mp.billing.repository.CreditNoteRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceItemRepository;
import com.costonomy.mp.billing.repository.TaxInvoiceRepository;
import com.costonomy.mp.billing.web.dto.BillingDtos;
import com.costonomy.mp.catalog.domain.CanonicalProduct;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceService {

    private final TaxInvoiceRepository invoices;
    private final TaxInvoiceItemRepository invoiceItems;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteItemRepository creditNoteItems;
    private final SupplierOrderRepository orders;
    private final SupplierOrderItemRepository orderItems;
    private final CanonicalProductRepository products;
    private final AccessControlService accessControl;
    private final JdbcTemplate jdbc;

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * Generate or return existing statutory Tax Invoice for an order.
     */
    @Transactional
    public BillingDtos.TaxInvoiceResponse generateOrGetInvoice(Long actorId, Long supplierOrderId) {
        SupplierOrder order = orders.findById(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", supplierOrderId));
        if (actorId != null) {
            requireAccess(actorId, order);
        }

        var existing = invoices.findBySupplierOrderId(supplierOrderId);
        if (existing.isPresent()) {
            return toInvoiceResponse(existing.get());
        }

        List<SupplierOrderItem> items = orderItems.findBySupplierOrderId(supplierOrderId);

        // Fetch supplier & restaurant details across module edges
        SupplierDetails supplier = loadSupplierDetails(order.getSupplierStoreId());
        RestaurantDetails restaurant = loadRestaurantDetails(order.getOutletId());

        String supplierStateCode = extractStateCode(supplier.gstin, supplier.state);
        String buyerStateCode = extractStateCode(restaurant.gstin, restaurant.state);
        boolean isInterState = supplierStateCode != null && buyerStateCode != null
                && !supplierStateCode.equalsIgnoreCase(buyerStateCode);

        String monthStr = YearMonth.now().format(YYYYMM);
        String invoiceNumber = "INV-%s-%05d".formatted(monthStr, order.getId());

        TaxInvoice invoice = new TaxInvoice();
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setSupplierOrderId(order.getId());
        invoice.setSupplierStoreId(order.getSupplierStoreId());
        invoice.setSupplierOrganizationId(supplier.orgId);
        invoice.setOutletId(order.getOutletId());
        invoice.setRestaurantId(restaurant.restaurantId);
        invoice.setSupplierName(supplier.displayName);
        invoice.setSupplierGstin(supplier.gstin);
        invoice.setSupplierAddress(supplier.address);
        invoice.setSupplierStateCode(supplierStateCode);
        invoice.setBuyerName(restaurant.restaurantName);
        invoice.setBuyerGstin(restaurant.gstin);
        invoice.setBuyerAddress(restaurant.address);
        invoice.setBuyerStateCode(buyerStateCode);
        invoice.setPlaceOfSupply(restaurant.state != null ? restaurant.state : supplier.state);
        invoice.setInterState(isInterState);
        invoice.setDeliveryFee(order.getDeliveryFee() != null ? order.getDeliveryFee() : BigDecimal.ZERO);
        invoice.setStatus("ISSUED");
        invoice.setIssuedAt(Instant.now());

        BigDecimal totalTaxable = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;

        List<TaxInvoiceItem> lineEntities = new ArrayList<>();

        for (SupplierOrderItem item : items) {
            BigDecimal qty = item.getDispatchedWeight() != null ? item.getDispatchedWeight()
                    : (item.getAcceptedQuantity() != null ? item.getAcceptedQuantity() : item.getRequestedQuantity());

            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal unitPrice = item.getUnitPriceSnapshot();
            BigDecimal taxable = Pricing.lineItemValue(unitPrice, qty);
            BigDecimal rate = item.getGstRateSnapshot() != null ? item.getGstRateSnapshot() : BigDecimal.ZERO;
            BigDecimal gst = Pricing.lineGst(taxable, rate);

            BigDecimal cgst = BigDecimal.ZERO;
            BigDecimal sgst = BigDecimal.ZERO;
            BigDecimal igst = BigDecimal.ZERO;

            if (isInterState) {
                igst = gst;
            } else {
                cgst = gst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                sgst = gst.subtract(cgst);
            }

            BigDecimal lineTotal = Pricing.lineTotal(taxable, gst);

            totalTaxable = totalTaxable.add(taxable);
            totalCgst = totalCgst.add(cgst);
            totalSgst = totalSgst.add(sgst);
            totalIgst = totalIgst.add(igst);

            TaxInvoiceItem line = new TaxInvoiceItem();
            line.setSupplierOrderItemId(item.getId());
            line.setProductName(resolveProductName(item.getCanonicalProductId()));
            line.setHsnCode(item.getHsnCode() != null ? item.getHsnCode() : "9968");
            line.setQuantity(qty);
            line.setUnit(item.getUnit());
            line.setUnitPrice(unitPrice);
            line.setTaxableValue(taxable);
            line.setGstRate(rate);
            line.setCgstAmount(cgst);
            line.setSgstAmount(sgst);
            line.setIgstAmount(igst);
            line.setTotalAmount(lineTotal);

            lineEntities.add(line);
        }

        BigDecimal grandTotal = totalTaxable.add(totalCgst).add(totalSgst).add(totalIgst).add(invoice.getDeliveryFee());

        invoice.setTaxableAmount(Pricing.money(totalTaxable));
        invoice.setCgstAmount(Pricing.money(totalCgst));
        invoice.setSgstAmount(Pricing.money(totalSgst));
        invoice.setIgstAmount(Pricing.money(totalIgst));
        invoice.setTotalAmount(Pricing.money(grandTotal));

        invoices.save(invoice);

        for (TaxInvoiceItem line : lineEntities) {
            line.setTaxInvoiceId(invoice.getId());
        }
        invoiceItems.saveAll(lineEntities);

        log.info("Statutory Tax Invoice {} created for order {}", invoiceNumber, supplierOrderId);
        return toInvoiceResponse(invoice, lineEntities);
    }

    /**
     * Generate statutory Credit Note under Section 34 of CGST Act for doorstep line rejections.
     */
    @Transactional
    public BillingDtos.CreditNoteResponse generateCreditNoteForRejection(
            Long supplierOrderId, String reasonCode) {

        SupplierOrder order = orders.findById(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", supplierOrderId));

        List<SupplierOrderItem> rejectedItems = orderItems.findBySupplierOrderId(supplierOrderId).stream()
                .filter(i -> i.getDoorstepRejectedQty() != null && i.getDoorstepRejectedQty().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (rejectedItems.isEmpty()) {
            return null;
        }

        var invoiceOpt = invoices.findBySupplierOrderId(supplierOrderId);
        TaxInvoice invoice = invoiceOpt.orElse(null);

        SupplierDetails supplier = loadSupplierDetails(order.getSupplierStoreId());
        RestaurantDetails restaurant = loadRestaurantDetails(order.getOutletId());

        String supplierStateCode = extractStateCode(supplier.gstin, supplier.state);
        String buyerStateCode = extractStateCode(restaurant.gstin, restaurant.state);
        boolean isInterState = supplierStateCode != null && buyerStateCode != null
                && !supplierStateCode.equalsIgnoreCase(buyerStateCode);

        String monthStr = YearMonth.now().format(YYYYMM);
        int existingCount = creditNotes.findBySupplierOrderIdOrderByIdAsc(supplierOrderId).size();
        String cnNumber = "CN-%s-%05d-%02d".formatted(monthStr, order.getId(), existingCount + 1);

        CreditNote note = new CreditNote();
        note.setCreditNoteNumber(cnNumber);
        note.setTaxInvoiceId(invoice != null ? invoice.getId() : null);
        note.setTaxInvoiceNumber(invoice != null ? invoice.getInvoiceNumber() : null);
        note.setSupplierOrderId(order.getId());
        note.setSupplierStoreId(order.getSupplierStoreId());
        note.setSupplierOrganizationId(supplier.orgId);
        note.setOutletId(order.getOutletId());
        note.setRestaurantId(restaurant.restaurantId);
        note.setSupplierName(supplier.displayName);
        note.setSupplierGstin(supplier.gstin);
        note.setBuyerName(restaurant.restaurantName);
        note.setBuyerGstin(restaurant.gstin);
        note.setReasonCode(reasonCode != null ? reasonCode : "DOORSTEP_REJECTION");
        note.setInterState(isInterState);
        note.setStatus("ISSUED");
        note.setIssuedAt(Instant.now());

        BigDecimal totalTaxable = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        List<CreditNoteItem> lineEntities = new ArrayList<>();

        for (SupplierOrderItem item : rejectedItems) {
            BigDecimal rejectedQty = item.getDoorstepRejectedQty();
            if (rejectedQty == null || rejectedQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal unitPrice = item.getUnitPriceSnapshot();
            BigDecimal taxable = Pricing.lineItemValue(unitPrice, rejectedQty);
            BigDecimal rate = item.getGstRateSnapshot() != null ? item.getGstRateSnapshot() : BigDecimal.ZERO;
            BigDecimal gst = Pricing.lineGst(taxable, rate);

            BigDecimal cgst = BigDecimal.ZERO;
            BigDecimal sgst = BigDecimal.ZERO;
            BigDecimal igst = BigDecimal.ZERO;

            if (isInterState) {
                igst = gst;
            } else {
                cgst = gst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                sgst = gst.subtract(cgst);
            }

            BigDecimal lineRefund = Pricing.lineTotal(taxable, gst);

            totalTaxable = totalTaxable.add(taxable);
            totalCgst = totalCgst.add(cgst);
            totalSgst = totalSgst.add(sgst);
            totalIgst = totalIgst.add(igst);
            grandTotal = grandTotal.add(lineRefund);

            CreditNoteItem line = new CreditNoteItem();
            line.setSupplierOrderItemId(item.getId());
            line.setProductName(resolveProductName(item.getCanonicalProductId()));
            line.setHsnCode(item.getHsnCode() != null ? item.getHsnCode() : "9968");
            line.setRejectedQuantity(rejectedQty);
            line.setUnit(item.getUnit());
            line.setUnitPrice(unitPrice);
            line.setTaxableRefund(taxable);
            line.setGstRate(rate);
            line.setCgstRefund(cgst);
            line.setSgstRefund(sgst);
            line.setIgstRefund(igst);
            line.setTotalRefund(lineRefund);
            line.setRejectionReason(item.getDoorstepRejectionReason());

            lineEntities.add(line);
        }

        note.setTaxableRefundAmount(Pricing.money(totalTaxable));
        note.setCgstRefundAmount(Pricing.money(totalCgst));
        note.setSgstRefundAmount(Pricing.money(totalSgst));
        note.setIgstRefundAmount(Pricing.money(totalIgst));
        note.setTotalRefundAmount(Pricing.money(grandTotal));

        creditNotes.save(note);

        for (CreditNoteItem line : lineEntities) {
            line.setCreditNoteId(note.getId());
        }
        creditNoteItems.saveAll(lineEntities);

        log.info("Statutory Credit Note {} created for order {} total refund {}",
                cnNumber, supplierOrderId, grandTotal);
        return toCreditNoteResponse(note, lineEntities);
    }

    @Transactional(readOnly = true)
    public BillingDtos.TaxInvoiceResponse getInvoiceForOrder(Long actorId, Long supplierOrderId) {
        SupplierOrder order = orders.findById(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", supplierOrderId));
        requireAccess(actorId, order);

        return invoices.findBySupplierOrderId(supplierOrderId)
                .map(inv -> toInvoiceResponse(inv, null))
                .orElseThrow(() -> new NotFoundException("TaxInvoice for order", supplierOrderId));
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.CreditNoteResponse> getCreditNotesForOrder(Long actorId, Long supplierOrderId) {
        SupplierOrder order = orders.findById(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("SupplierOrder", supplierOrderId));
        requireAccess(actorId, order);

        return creditNotes.findBySupplierOrderIdOrderByIdAsc(supplierOrderId).stream()
                .map(cn -> toCreditNoteResponse(cn, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public String generateTallyXml(Long actorId, Long supplierOrderId) {
        BillingDtos.TaxInvoiceResponse invoice = getInvoiceForOrder(actorId, supplierOrderId);
        String dateStr = invoice.issuedAt().toString().substring(0, 10).replace("-", "");

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ENVELOPE>\n");
        sb.append("  <HEADER>\n");
        sb.append("    <TALLYREQUEST>Import Data</TALLYREQUEST>\n");
        sb.append("  </HEADER>\n");
        sb.append("  <BODY>\n");
        sb.append("    <IMPORTDATA>\n");
        sb.append("      <REQUESTDESC>\n");
        sb.append("        <REPORTNAME>Vouchers</REPORTNAME>\n");
        sb.append("      </REQUESTDESC>\n");
        sb.append("      <REQUESTDATA>\n");
        sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
        sb.append("          <VOUCHER VCHTYPE=\"Sales\" ACTION=\"Create\">\n");
        sb.append("            <DATE>").append(dateStr).append("</DATE>\n");
        sb.append("            <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>\n");
        sb.append("            <VOUCHERNUMBER>").append(escapeXml(invoice.invoiceNumber())).append("</VOUCHERNUMBER>\n");
        sb.append("            <PARTYLEDGERNAME>").append(escapeXml(invoice.buyerName())).append("</PARTYLEDGERNAME>\n");
        sb.append("            <PARTYNAME>").append(escapeXml(invoice.buyerName())).append("</PARTYNAME>\n");
        sb.append("            <BASICBUYERNAME>").append(escapeXml(invoice.buyerName())).append("</BASICBUYERNAME>\n");
        sb.append("            <PLACEOFSUPPLY>").append(escapeXml(invoice.placeOfSupply() != null ? invoice.placeOfSupply() : "")).append("</PLACEOFSUPPLY>\n");

        sb.append("            <ALLLEDGERENTRIES.LIST>\n");
        sb.append("              <LEDGERNAME>").append(escapeXml(invoice.buyerName())).append("</LEDGERNAME>\n");
        sb.append("              <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>\n");
        sb.append("              <AMOUNT>-").append(invoice.totalAmount().toPlainString()).append("</AMOUNT>\n");
        sb.append("            </ALLLEDGERENTRIES.LIST>\n");

        sb.append("            <ALLLEDGERENTRIES.LIST>\n");
        sb.append("              <LEDGERNAME>Sales Account</LEDGERNAME>\n");
        sb.append("              <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>\n");
        sb.append("              <AMOUNT>").append(invoice.taxableAmount().toPlainString()).append("</AMOUNT>\n");
        sb.append("            </ALLLEDGERENTRIES.LIST>\n");

        if (invoice.cgstAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("            <ALLLEDGERENTRIES.LIST>\n");
            sb.append("              <LEDGERNAME>CGST Output</LEDGERNAME>\n");
            sb.append("              <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>\n");
            sb.append("              <AMOUNT>").append(invoice.cgstAmount().toPlainString()).append("</AMOUNT>\n");
            sb.append("            </ALLLEDGERENTRIES.LIST>\n");
        }
        if (invoice.sgstAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("            <ALLLEDGERENTRIES.LIST>\n");
            sb.append("              <LEDGERNAME>SGST Output</LEDGERNAME>\n");
            sb.append("              <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>\n");
            sb.append("              <AMOUNT>").append(invoice.sgstAmount().toPlainString()).append("</AMOUNT>\n");
            sb.append("            </ALLLEDGERENTRIES.LIST>\n");
        }
        if (invoice.igstAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("            <ALLLEDGERENTRIES.LIST>\n");
            sb.append("              <LEDGERNAME>IGST Output</LEDGERNAME>\n");
            sb.append("              <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>\n");
            sb.append("              <AMOUNT>").append(invoice.igstAmount().toPlainString()).append("</AMOUNT>\n");
            sb.append("            </ALLLEDGERENTRIES.LIST>\n");
        }
        if (invoice.deliveryFee().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("            <ALLLEDGERENTRIES.LIST>\n");
            sb.append("              <LEDGERNAME>Freight & Carriage</LEDGERNAME>\n");
            sb.append("              <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>\n");
            sb.append("              <AMOUNT>").append(invoice.deliveryFee().toPlainString()).append("</AMOUNT>\n");
            sb.append("            </ALLLEDGERENTRIES.LIST>\n");
        }

        sb.append("          </VOUCHER>\n");
        sb.append("        </TALLYMESSAGE>\n");
        sb.append("      </REQUESTDATA>\n");
        sb.append("    </IMPORTDATA>\n");
        sb.append("  </BODY>\n");
        sb.append("</ENVELOPE>\n");
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String generateGstr1Csv(Long actorId, Long supplierOrderId) {
        BillingDtos.TaxInvoiceResponse invoice = getInvoiceForOrder(actorId, supplierOrderId);
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH);
        String invDate = java.time.LocalDate.ofInstant(invoice.issuedAt(), java.time.ZoneId.systemDefault()).format(dtf);

        StringBuilder sb = new StringBuilder();
        sb.append("GSTIN/UIN of Recipient,Receiver Name,Invoice Number,Invoice date,Invoice Value,Place Of Supply,Reverse Charge,Applicable % of Tax Rate,Invoice Type,E-Commerce GSTIN,Rate,Taxable Value,Cess Amount\n");

        for (BillingDtos.TaxInvoiceItemResponse item : invoice.items()) {
            sb.append("\"").append(invoice.buyerGstin() != null ? invoice.buyerGstin() : "").append("\",");
            sb.append("\"").append(escapeCsv(invoice.buyerName())).append("\",");
            sb.append("\"").append(escapeCsv(invoice.invoiceNumber())).append("\",");
            sb.append("\"").append(invDate).append("\",");
            sb.append(invoice.totalAmount().toPlainString()).append(",");
            sb.append("\"").append(invoice.placeOfSupply() != null ? invoice.placeOfSupply() : "").append("\",");
            sb.append("\"N\",,"); // Reverse Charge, Applicable %
            sb.append("\"Regular\",,"); // Invoice Type, E-Commerce GSTIN
            sb.append(item.gstRate().toPlainString()).append(",");
            sb.append(item.taxableValue().toPlainString()).append(",");
            sb.append("0.00\n");
        }
        return sb.toString();
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String escapeCsv(String text) {
        if (text == null) return "";
        return text.replace("\"", "\"\"");
    }

    // ── Internal Helpers ──────────────────────────────────────────────────

    private void requireAccess(Long actorId, SupplierOrder order) {
        if (accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, order.getOutletId())
                || accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.SUPPLIER_STORE, order.getSupplierStoreId())) {
            return;
        }
        throw new NotFoundException("SupplierOrder", order.getId());
    }

    private BillingDtos.TaxInvoiceResponse toInvoiceResponse(TaxInvoice inv) {
        return toInvoiceResponse(inv, null);
    }

    private BillingDtos.CreditNoteResponse toCreditNoteResponse(CreditNote cn) {
        return toCreditNoteResponse(cn, null);
    }

    private BillingDtos.TaxInvoiceResponse toInvoiceResponse(TaxInvoice inv, List<TaxInvoiceItem> lines) {
        List<TaxInvoiceItem> source = lines != null ? lines
                : invoiceItems.findByTaxInvoiceIdOrderByIdAsc(inv.getId());

        var items = source.stream()
                .map(i -> new BillingDtos.TaxInvoiceItemResponse(
                        i.getId(), i.getSupplierOrderItemId(), i.getProductName(),
                        i.getHsnCode(), i.getQuantity(), i.getUnit(), i.getUnitPrice(),
                        i.getTaxableValue(), i.getGstRate(), i.getCgstAmount(),
                        i.getSgstAmount(), i.getIgstAmount(), i.getTotalAmount()))
                .toList();

        var order = orders.findById(inv.getSupplierOrderId()).orElse(null);
        String orderNumber = order != null ? order.getOrderNumber() : null;

        return new BillingDtos.TaxInvoiceResponse(
                inv.getId(), inv.getInvoiceNumber(), inv.getSupplierOrderId(), orderNumber,
                inv.getSupplierStoreId(), inv.getSupplierName(), inv.getSupplierGstin(),
                inv.getSupplierAddress(), inv.getSupplierStateCode(),
                inv.getOutletId(), inv.getRestaurantId(), inv.getBuyerName(), inv.getBuyerGstin(),
                inv.getBuyerAddress(), inv.getBuyerStateCode(), inv.getPlaceOfSupply(),
                inv.isInterState(), inv.getTaxableAmount(), inv.getCgstAmount(),
                inv.getSgstAmount(), inv.getIgstAmount(), inv.getDeliveryFee(),
                inv.getTotalAmount(), inv.getStatus(), inv.getIssuedAt(), items);
    }

    private BillingDtos.CreditNoteResponse toCreditNoteResponse(CreditNote cn, List<CreditNoteItem> lines) {
        List<CreditNoteItem> source = lines != null ? lines
                : creditNoteItems.findByCreditNoteIdOrderByIdAsc(cn.getId());

        var items = source.stream()
                .map(i -> new BillingDtos.CreditNoteItemResponse(
                        i.getId(), i.getSupplierOrderItemId(), i.getProductName(),
                        i.getHsnCode(), i.getRejectedQuantity(), i.getUnit(), i.getUnitPrice(),
                        i.getTaxableRefund(), i.getGstRate(), i.getCgstRefund(),
                        i.getSgstRefund(), i.getIgstRefund(), i.getTotalRefund(),
                        i.getRejectionReason()))
                .toList();

        var order = orders.findById(cn.getSupplierOrderId()).orElse(null);
        String orderNumber = order != null ? order.getOrderNumber() : null;

        return new BillingDtos.CreditNoteResponse(
                cn.getId(), cn.getCreditNoteNumber(), cn.getTaxInvoiceId(), cn.getTaxInvoiceNumber(),
                cn.getSupplierOrderId(), orderNumber, cn.getSupplierStoreId(), cn.getSupplierName(),
                cn.getSupplierGstin(), cn.getOutletId(), cn.getRestaurantId(), cn.getBuyerName(),
                cn.getBuyerGstin(), cn.getReasonCode(), cn.isInterState(),
                cn.getTaxableRefundAmount(), cn.getCgstRefundAmount(), cn.getSgstRefundAmount(),
                cn.getIgstRefundAmount(), cn.getTotalRefundAmount(), cn.getStatus(),
                cn.getIssuedAt(), items);
    }

    private String resolveProductName(Long canonicalProductId) {
        if (canonicalProductId == null) return "Item";
        return products.findById(canonicalProductId)
                .map(CanonicalProduct::getName)
                .orElse("Item");
    }

    private static String extractStateCode(String gstin, String stateName) {
        if (gstin != null && gstin.trim().length() >= 2) {
            String prefix = gstin.trim().substring(0, 2);
            if (prefix.chars().allMatch(Character::isDigit)) {
                return prefix;
            }
        }
        if (stateName == null) return "36"; // Default Telangana state code
        String s = stateName.toLowerCase();
        if (s.contains("telangana")) return "36";
        if (s.contains("andhra")) return "37";
        if (s.contains("karnataka")) return "29";
        if (s.contains("maharashtra")) return "27";
        if (s.contains("tamil")) return "33";
        if (s.contains("delhi")) return "07";
        return "36";
    }

    private record SupplierDetails(Long orgId, String displayName, String gstin, String address, String state) {}
    private record RestaurantDetails(Long restaurantId, String restaurantName, String gstin, String address, String state) {}

    private SupplierDetails loadSupplierDetails(Long supplierStoreId) {
        var res = jdbc.query("""
                select o.id, o.display_name, o.gstin,
                       concat_ws(', ', s.address_line1, s.address_line2, s.city, s.pincode),
                       s.state
                  from supplier_store s
                  join supplier_organization o on o.id = s.supplier_organization_id
                 where s.id = ?
                """, rs -> {
            if (rs.next()) {
                return new SupplierDetails(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5));
            }
            return null;
        }, supplierStoreId);
        return res != null ? res : new SupplierDetails(1L, "Supplier", null, "Supplier Address", "Telangana");
    }

    private RestaurantDetails loadRestaurantDetails(Long outletId) {
        var res = jdbc.query("""
                select r.id, r.name, r.gstin,
                       concat_ws(', ', o.address_line1, o.address_line2, o.city, o.pincode),
                       o.state
                  from outlet o
                  join restaurant r on r.id = o.restaurant_id
                 where o.id = ?
                """, rs -> {
            if (rs.next()) {
                return new RestaurantDetails(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5));
            }
            return null;
        }, outletId);
        return res != null ? res : new RestaurantDetails(1L, "Restaurant", null, "Restaurant Address", "Telangana");
    }
}
