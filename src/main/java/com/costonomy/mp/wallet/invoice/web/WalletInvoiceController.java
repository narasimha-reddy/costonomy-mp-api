package com.costonomy.mp.wallet.invoice.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.wallet.invoice.service.WalletInvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * The shop's bill on a wallet payment (D-113). Reading it is the same door as the transaction details: ORDER_VIEW on
 * the outlet. Changing it (upload, review, remove) also needs QUICKSCAN_PAY, the permission that pays a shop from
 * the wallet (owner, admin, purchase manager, finance), because the review restates what was bought and paid
 * (D-115). A user who can see the outlet but not pay gets 403 FORBIDDEN.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Wallet")
public class WalletInvoiceController {

    private final AccessControlService accessControl;
    private final WalletInvoiceService service;

    @PostMapping(value = "/outlets/{outletId}/wallet/transactions/{entryId}/invoice",
            consumes = "multipart/form-data")
    @Operation(summary = "Attach the shop's bill to a wallet payment",
            description = """
                    1 to 5 parts named `file`: JPEG, PNG, WebP or PDF, 5 MB each, checked by their real bytes.
                    Only for an order payment or a QuickScan payment (422 otherwise). One bill per payment
                    (409 INVOICE_EXISTS). Returns 201 with status READING; the bill is read afterwards.""")
    public ResponseEntity<ApiResponse<InvoiceDtos.Invoice>> upload(
            @PathVariable Long outletId, @PathVariable String entryId,
            @RequestParam(value = "file", required = false) List<MultipartFile> files) {
        Long actorId = ActorContext.requireUserId();
        requireWrite(actorId, outletId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.upload(outletId, entryId, actorId, files)));
    }

    @GetMapping("/outlets/{outletId}/wallet/transactions/{entryId}/invoice")
    @Operation(summary = "The bill on a wallet payment, with short-lived page links",
            description = "404 INVOICE_NOT_FOUND when the payment exists but has no bill.")
    public ApiResponse<InvoiceDtos.Invoice> get(@PathVariable Long outletId, @PathVariable String entryId) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        return ApiResponse.ok(service.get(outletId, entryId));
    }

    @DeleteMapping("/outlets/{outletId}/wallet/transactions/{entryId}/invoice")
    @Operation(summary = "Remove the bill and its stored pages")
    public ResponseEntity<Void> delete(@PathVariable Long outletId, @PathVariable String entryId) {
        Long actorId = ActorContext.requireUserId();
        requireWrite(actorId, outletId);
        service.delete(outletId, entryId, actorId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/outlets/{outletId}/wallet/transactions/{entryId}/invoice/review")
    @Operation(summary = "Save the user's review of the bill (D-114, D-115)",
            description = """
                    The reviewed supplier, invoice number, dates, payment status, lines, delivery and `taxOverride`.
                    Money is computed by the server: subtotal = sum of line amounts; tax = `taxOverride` when set,
                    else the sum of line taxes; total = subtotal + tax + delivery. The reading is never changed.
                    `lineNo` is null for a line the user added and 1..N (each once) for a line from the bill;
                    `fromInvoice` and `deliveryOverridden` in the body are ignored (derived by the server).
                    `stockInDate` may be at most one day after today (Asia/Kolkata). Quantity: at most 9 digits and
                    3 decimals; amounts, tax, delivery: 12 digits and 2 decimals; a SKU's unitPrice: 12 digits and 4
                    decimals. Only when the bill is READ or UNREADABLE (409 INVOICE_STILL_READING while reading);
                    `version` must be the bill's current one (409 INVOICE_CHANGED).
                    Optional `Idempotency-Key` (at most 128 characters): the same key with the same body again
                    answers 200 with the bill as it is now (no new version); the same key with another body is 422
                    IDEMPOTENCY_KEY_REUSED. Needs QUICKSCAN_PAY on the outlet (403 otherwise). Nothing is written to
                    the cost app.""")
    public ApiResponse<InvoiceDtos.Invoice> review(
            @PathVariable Long outletId, @PathVariable String entryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) InvoiceReviewDtos.ReviewRequest body) {
        Long actorId = ActorContext.requireUserId();
        requireWrite(actorId, outletId);
        return ApiResponse.ok(service.review(outletId, entryId, actorId, body, idempotencyKey));
    }

    /** Inside the outlet (else 404, as for reading), then allowed to pay from its wallet (else 403). */
    private void requireWrite(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        accessControl.require(actorId, Permissions.QUICKSCAN_PAY, ScopeType.OUTLET, outletId);
    }
}
