package com.costonomy.mp.billing.web;

import com.costonomy.mp.billing.service.TaxInvoiceService;
import com.costonomy.mp.billing.web.dto.BillingDtos;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Billing & GST Invoices", description = "Statutory GST Tax Invoices and Credit Notes")
public class TaxInvoiceController {

    private final TaxInvoiceService invoiceService;

    @GetMapping({"/supplier-orders/{orderId}/tax-invoice", "/orders/{orderId}/tax-invoice"})
    @Operation(summary = "Get statutory Tax Invoice for an order")
    public ApiResponse<BillingDtos.TaxInvoiceResponse> getTaxInvoice(
            @PathVariable Long orderId) {
        return ApiResponse.ok(invoiceService.getInvoiceForOrder(ActorContext.requireUserId(), orderId));
    }

    @PostMapping({"/supplier-orders/{orderId}/tax-invoice/generate", "/orders/{orderId}/tax-invoice/generate"})
    @Operation(summary = "Generate statutory Tax Invoice for an order")
    public ApiResponse<BillingDtos.TaxInvoiceResponse> generateTaxInvoice(
            @PathVariable Long orderId) {
        return ApiResponse.ok(invoiceService.generateOrGetInvoice(orderId));
    }

    @GetMapping({"/supplier-orders/{orderId}/credit-notes", "/orders/{orderId}/credit-notes"})
    @Operation(summary = "Get statutory Credit Notes for an order")
    public ApiResponse<List<BillingDtos.CreditNoteResponse>> getCreditNotes(
            @PathVariable Long orderId) {
        return ApiResponse.ok(invoiceService.getCreditNotesForOrder(ActorContext.requireUserId(), orderId));
    }
}
