package com.costonomy.mp.supplier.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.supplier.service.SupplierStandingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Operations endpoint for a supplier's activation. Doc 04 §19, doc 09 §9. Verification itself is reviewed in
 * costonomy-common-api's verification queue (D-208); activating a verified supplier is the Marketplace's decision.
 * Requires {@code SUPPLIER_VERIFY} at platform scope and is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/suppliers")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — Supplier verification")
public class SupplierVerificationAdminController {

    private final SupplierStandingService standings;

    @PostMapping("/{id}/activate")
    @Operation(
            summary = "Activate a verified supplier",
            description = "The point at which a supplier can receive orders.")
    public ApiResponse<SupplierStandingService.Standing> activate(@PathVariable Long id) {
        return ApiResponse.ok(standings.activate(ActorContext.requireUserId(), id));
    }
}
