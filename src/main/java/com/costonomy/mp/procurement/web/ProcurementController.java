package com.costonomy.mp.procurement.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.procurement.service.ProcurementService;
import com.costonomy.mp.procurement.service.ProcurementSubmissionService;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Cart, checkout, approval and submission. Doc 04 §10. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Procurement")
public class ProcurementController {

    private final ProcurementService procurement;
    private final ProcurementSubmissionService submission;

    @GetMapping("/outlets/{outletId}/cart")
    @Operation(
            summary = "The outlet's open cart",
            description = "One cart per outlet, so adding from search, a product page "
                    + "or a recommendation all land in the same place.")
    public ApiResponse<ProcurementDtos.ProcurementResponse> cart(@PathVariable Long outletId) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(procurement.get(actorId, procurement.openCart(actorId, outletId).getId()));
    }

    @PostMapping("/outlets/{outletId}/cart/items")
    @Operation(
            summary = "Add an offer to the cart",
            description = """
                    Adding a SKU already in the cart increases its quantity rather than
                    creating a second line. Pass `requirementItemId` to link the line to
                    the need it serves, so an accepted quantity credits back to it.
                    """)
    public ApiResponse<ProcurementDtos.ProcurementResponse> addItem(
            @PathVariable Long outletId,
            @Valid @RequestBody ProcurementDtos.AddCartItemRequest request) {
        return ApiResponse.ok(procurement.addItem(ActorContext.requireUserId(), outletId, request));
    }

    @PatchMapping("/procurement-items/{itemId}")
    @Operation(summary = "Change a line's quantity")
    public ApiResponse<ProcurementDtos.ProcurementResponse> updateItem(
            @PathVariable Long itemId,
            @Valid @RequestBody ProcurementDtos.UpdateCartItemRequest request) {
        return ApiResponse.ok(procurement.updateItemQuantity(
                ActorContext.requireUserId(), itemId, request.quantity()));
    }

    @DeleteMapping("/procurement-items/{itemId}")
    @Operation(summary = "Remove a line")
    public ApiResponse<ProcurementDtos.ProcurementResponse> removeItem(@PathVariable Long itemId) {
        return ApiResponse.ok(procurement.removeItem(ActorContext.requireUserId(), itemId));
    }

    @PatchMapping("/procurements/{id}/payment-method")
    @Operation(
            summary = "Choose PREPAID or CREDIT",
            description = "Changing this can change whether the order needs approval, "
                    + "so it invalidates the current validation.")
    public ApiResponse<ProcurementDtos.ProcurementResponse> setPaymentMethod(
            @PathVariable Long id,
            @Valid @RequestBody ProcurementDtos.SetPaymentMethodRequest request) {
        return ApiResponse.ok(procurement.setPaymentMethod(
                ActorContext.requireUserId(), id, request.paymentMethod()));
    }

    @GetMapping("/procurements/{id}")
    @Operation(summary = "Get a cart or order, grouped by supplier")
    public ApiResponse<ProcurementDtos.ProcurementResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(procurement.get(ActorContext.requireUserId(), id));
    }

    @PostMapping("/procurements/{id}/validate")
    @Operation(
            summary = "Re-check the cart against live offers",
            description = """
                    Checks every line: the SKU is still listed, the supplier is still
                    trading, the offer is still available, and the price and GST have not
                    moved.

                    **A changed price is reported, not applied.** `priceChanges` carries
                    the old and new figures and the order cannot be submitted until you
                    call this again with `acceptPriceChanges: true` — an explicit yes to
                    the new figure. Anything else would be a silent reprice.

                    Also evaluates approval policy. Whether an order needs approval is
                    decided here, by the server; a client cannot request or skip it.
                    """)
    public ApiResponse<ProcurementDtos.ProcurementResponse> validate(
            @PathVariable Long id,
            @RequestBody(required = false) ProcurementDtos.ValidateRequest request) {
        boolean accept = request != null && request.acceptPriceChanges();
        return ApiResponse.ok(procurement.validate(ActorContext.requireUserId(), id, accept));
    }

    @PostMapping("/procurements/{id}/submit")
    @Operation(
            summary = "Place the order",
            description = """
                    Creates one supplier order per supplier, each with its own acceptance
                    deadline snapshotted from that store's SLA.

                    Everything validation checked is checked again inside the creating
                    transaction — the client's `submittable` was true a moment ago, and
                    offers move.

                    Requires an `Idempotency-Key`. A retry with the same key returns the
                    original response; a retry without one is still caught by a unique
                    constraint, because a duplicate submission would place two real orders
                    with two real suppliers.
                    """)
    public ApiResponse<ProcurementDtos.SubmitResponse> submit(
            @PathVariable Long id,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(submission.submit(ActorContext.requireUserId(), id, idempotencyKey));
    }

    @PostMapping("/procurements/{id}/approve")
    @Operation(
            summary = "Approve an order held by policy",
            description = "Requires PROCUREMENT_APPROVE at the outlet. Editing the cart "
                    + "after approval clears it — an approved order whose contents "
                    + "changed is not an approved order.")
    public ApiResponse<ProcurementDtos.ProcurementResponse> approve(@PathVariable Long id) {
        return ApiResponse.ok(procurement.approve(ActorContext.requireUserId(), id));
    }

    @PostMapping("/procurements/{id}/reject")
    @Operation(
            summary = "Reject an order held by policy",
            description = "Returns it to DRAFT so the requester can adjust and resubmit, "
                    + "rather than having to rebuild the cart.")
    public ApiResponse<ProcurementDtos.ProcurementResponse> reject(
            @PathVariable Long id,
            @RequestBody(required = false) ProcurementDtos.RejectRequest request) {
        return ApiResponse.ok(procurement.reject(ActorContext.requireUserId(), id,
                request == null ? null : request.reason()));
    }

    @PostMapping("/procurements/{id}/cancel")
    @Operation(summary = "Cancel a cart or an unsubmitted order")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        procurement.cancel(ActorContext.requireUserId(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/procurements/{id}/supplier-orders")
    @Operation(summary = "The supplier orders this procurement produced")
    public ApiResponse<List<ProcurementDtos.SupplierOrderResponse>> supplierOrders(
            @PathVariable Long id) {
        return ApiResponse.ok(submission.ordersForProcurement(ActorContext.requireUserId(), id));
    }

    @GetMapping("/outlets/{outletId}/supplier-orders")
    @Operation(summary = "An outlet's orders")
    public ApiResponse<List<ProcurementDtos.SupplierOrderResponse>> outletOrders(
            @PathVariable Long outletId) {
        return ApiResponse.ok(submission.ordersForOutlet(ActorContext.requireUserId(), outletId));
    }

    @GetMapping("/supplier-orders/{id}")
    @Operation(
            summary = "Get a supplier order",
            description = "Visible to the restaurant that placed it and the supplier "
                    + "that received it, and to nobody else.")
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> order(@PathVariable Long id) {
        return ApiResponse.ok(submission.getOrder(ActorContext.requireUserId(), id));
    }
}
