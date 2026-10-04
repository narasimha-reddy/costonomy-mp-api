package com.costonomy.mp.wallet.invoice.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.wallet.invoice.costapi.CostCatalog;
import com.costonomy.mp.wallet.invoice.service.InvoiceLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The bill review screen's pickers (D-114): the cost app's suppliers and SKUs, read only. Same door as the bill:
 * ORDER_VIEW on the outlet. The cost app is asked for the cost outlet this outlet is mapped to (D-115), never another;
 * an outlet without one gets 403 INVOICE_LOOKUP_NOT_AVAILABLE. Unknown query parameters are ignored.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Wallet")
public class InvoiceLookupController {

    private final AccessControlService accessControl;
    private final InvoiceLookupService lookups;

    @GetMapping("/outlets/{outletId}/invoice-lookups/suppliers")
    @Operation(summary = "Suppliers for the bill review picker",
            description = """
                    `q` (0 to 60 characters, else 400) matches inside the name; `limit` 1 to 50, default 20. Answers
                    `[{id, name}]`. 403 INVOICE_LOOKUP_NOT_AVAILABLE when the outlet has no cost outlet.""")
    public ApiResponse<List<CostCatalog.SupplierOption>> suppliers(
            @PathVariable Long outletId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) Integer limit) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        return ApiResponse.ok(lookups.suppliers(outletId, q, limit));
    }

    @GetMapping("/outlets/{outletId}/invoice-lookups/skus")
    @Operation(summary = "SKUs for the bill review picker",
            description = """
                    `q` (0 to 60 characters, else 400) matches inside the name; `limit` 1 to 50, default 20. Answers
                    `[{id, name, unit, unitPrice (at most 4 decimals), categoryName}]`. There is no supplier filter:
                    the cost app's SKU list carries no supplier. 403 INVOICE_LOOKUP_NOT_AVAILABLE when the outlet has
                    no cost outlet.""")
    public ApiResponse<List<CostCatalog.SkuOption>> skus(
            @PathVariable Long outletId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) Integer limit) {
        Long actorId = ActorContext.requireUserId();
        accessControl.requireScoped(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId, "Outlet");
        return ApiResponse.ok(lookups.skus(outletId, q, limit));
    }
}
