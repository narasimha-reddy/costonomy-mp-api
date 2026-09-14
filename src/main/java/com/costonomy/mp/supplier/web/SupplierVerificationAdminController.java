package com.costonomy.mp.supplier.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.supplier.service.SupplierService;
import com.costonomy.mp.supplier.web.dto.SupplierDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Operations endpoints for supplier verification. Doc 04 §19, doc 09 §9.
 *
 * <p>No mobile UI consumes these — doc 27 is explicit that operations is a future
 * website and the backend APIs come first. Every action requires
 * {@code SUPPLIER_VERIFY} at platform scope and is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/suppliers")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — Supplier verification")
public class SupplierVerificationAdminController {

    private final SupplierService supplierService;

    public record ReviewRequest(
            @NotNull(message = "approved is required") Boolean approved,
            @Size(max = 500) String reason) {
    }

    @PostMapping("/verifications/{verificationId}/review")
    @Operation(
            summary = "Approve or reject a verification",
            description = """
                    Approval moves the supplier to VERIFIED, not ACTIVE — activation is a
                    separate call. Rejection returns them to REGISTERED so they can correct
                    and resubmit.
                    """)
    public ApiResponse<SupplierDtos.VerificationResponse> review(
            @PathVariable Long verificationId,
            @org.springframework.web.bind.annotation.RequestBody ReviewRequest request) {
        return ApiResponse.ok(supplierService.reviewVerification(
                ActorContext.requireUserId(), verificationId,
                Boolean.TRUE.equals(request.approved()), request.reason()));
    }

    @PostMapping("/{id}/activate")
    @Operation(
            summary = "Activate a verified supplier",
            description = "The point at which a supplier can receive orders.")
    public ApiResponse<SupplierDtos.SupplierResponse> activate(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.activate(ActorContext.requireUserId(), id));
    }
}
