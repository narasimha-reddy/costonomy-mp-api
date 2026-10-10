package com.costonomy.mp.billing.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class BillingDtos {

    public record TaxInvoiceResponse(
            Long id,
            String invoiceNumber,
            Long supplierOrderId,
            String orderNumber,
            Long supplierStoreId,
            String supplierName,
            String supplierGstin,
            String supplierAddress,
            String supplierStateCode,
            Long outletId,
            Long restaurantId,
            String buyerName,
            String buyerGstin,
            String buyerAddress,
            String buyerStateCode,
            String placeOfSupply,
            boolean isInterState,
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal deliveryFee,
            BigDecimal totalAmount,
            String status,
            Instant issuedAt,
            List<TaxInvoiceItemResponse> items
    ) {}

    public record TaxInvoiceItemResponse(
            Long id,
            Long supplierOrderItemId,
            String productName,
            String hsnCode,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal taxableValue,
            BigDecimal gstRate,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal totalAmount
    ) {}

    public record CreditNoteResponse(
            Long id,
            String creditNoteNumber,
            Long taxInvoiceId,
            String taxInvoiceNumber,
            Long supplierOrderId,
            String orderNumber,
            Long supplierStoreId,
            String supplierName,
            String supplierGstin,
            Long outletId,
            Long restaurantId,
            String buyerName,
            String buyerGstin,
            String reasonCode,
            boolean isInterState,
            BigDecimal taxableRefundAmount,
            BigDecimal cgstRefundAmount,
            BigDecimal sgstRefundAmount,
            BigDecimal igstRefundAmount,
            BigDecimal totalRefundAmount,
            String status,
            Instant issuedAt,
            List<CreditNoteItemResponse> items
    ) {}

    public record CreditNoteItemResponse(
            Long id,
            Long supplierOrderItemId,
            String productName,
            String hsnCode,
            BigDecimal rejectedQuantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal taxableRefund,
            BigDecimal gstRate,
            BigDecimal cgstRefund,
            BigDecimal sgstRefund,
            BigDecimal igstRefund,
            BigDecimal totalRefund,
            String rejectionReason
    ) {}
}
