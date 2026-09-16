package com.costonomy.mp.procurement.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.procurement.service.AlternativeSourcingService;
import com.costonomy.mp.procurement.service.SupplierOrderService;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import org.springframework.format.annotation.DateTimeFormat;
import java.time.Instant;
import java.util.List;

/** Supplier responses and alternative sourcing. Doc 04 §11, doc 15. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Supplier orders")
public class SupplierOrderController {

    private final SupplierOrderService supplierOrders;
    private final AlternativeSourcingService alternatives;

    @GetMapping("/supplier-stores/{storeId}/orders/pending")
    @Operation(
            summary = "Orders awaiting a response",
            description = """
                    Soonest deadline first — the supplier's inbox (doc 05 §24).

                    `secondsRemaining` is computed from the authoritative deadline so the
                    client starts its countdown from the server's clock, not the handset's.
                    The client still counts down against `acceptanceDeadline` itself.
                    """)
    public ApiResponse<List<ProcurementDtos.IncomingOrderResponse>> pending(
            @PathVariable Long storeId) {
        return ApiResponse.ok(supplierOrders.pendingForStore(ActorContext.requireUserId(), storeId));
    }

    @GetMapping("/supplier-stores/{storeId}/orders/active")
    @Operation(summary = "Accepted orders still being worked on")
    public ApiResponse<List<ProcurementDtos.IncomingOrderResponse>> active(
            @PathVariable Long storeId) {
        return ApiResponse.ok(supplierOrders.activeForStore(ActorContext.requireUserId(), storeId));
    }

    @GetMapping("/supplier-stores/{storeId}/orders")
    @Operation(
            summary = "This store's orders, by status and by when they arrived",
            description = """
                    Dated on when the order reached the store, which is the one date every
                    order has and the one a supplier means by "last week".

                    `status` may be repeated; omitted, it means every status a supplier is
                    allowed to see. That never includes DRAFT — an order is invisible until
                    it is funded — so asking for it returns nothing rather than leaking one.

                    An absent window defaults to the last seven days. Unbounded history is a
                    table scan that grows with the marketplace.
                    """)
    public ApiResponse<List<ProcurementDtos.IncomingOrderResponse>> history(
            @PathVariable Long storeId,
            @RequestParam(value = "status", required = false) List<SupplierOrderStatus> statuses,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        return ApiResponse.ok(supplierOrders.historyForStore(
                ActorContext.requireUserId(), storeId, statuses, from, to));
    }

    @PostMapping("/supplier-orders/{id}/accept")
    @Operation(
            summary = "Accept in full",
            description = """
                    Fails with `SUPPLIER_ORDER_EXPIRED` if the response window has closed —
                    including when the timeout job has not yet swept, because the deadline
                    is the authority, not the job.

                    If a timeout wins the race, that is what you are told: accepting a
                    moment too late reports expiry, not a concurrency error.
                    """)
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> accept(
            @PathVariable Long id,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.accept(ActorContext.requireUserId(), id, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/partial-accept")
    @Operation(
            summary = "Accept reduced quantities",
            description = """
                    Every line must be answered, **including with zero** — an omitted line
                    is ambiguous between declined and forgotten, and the restaurant needs
                    to know which.

                    Totals are recalculated from the accepted quantities at the prices
                    already agreed. Only that value is ever captured from the restaurant.

                    Zero on every line is recorded as a rejection, not as a partial
                    acceptance of nothing.

                    The shortfall stays on the requirement, ready to be sourced elsewhere.
                    """)
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> partialAccept(
            @PathVariable Long id,
            @Valid @RequestBody ProcurementDtos.PartialAcceptRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.partialAccept(
                ActorContext.requireUserId(), id, request, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/reject")
    @Operation(
            summary = "Decline an order",
            description = """
                    Requires one of the listed reasons: OUT_OF_STOCK, UNABLE_TO_DELIVER,
                    STORE_CLOSED, PRICE_ISSUE, BELOW_MINIMUM_ORDER, OTHER.

                    Recorded separately from a timeout: declining is a decision, not
                    answering is a failure to respond, and they mean different things.
                    """)
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody ProcurementDtos.RejectOrderRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.reject(
                ActorContext.requireUserId(), id, request, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/preparing")
    @Operation(summary = "Mark an accepted order as being prepared")
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> preparing(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.markPreparing(
                ActorContext.requireUserId(), id, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/ready")
    @Operation(
            summary = "Mark ready for pickup",
            description = """
                    Where delivery begins. Past this point the delivery provider owns the
                    order's movement — a supplier cannot claim pickup or delivery on a
                    provider's behalf (§23A.38).
                    """)
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> ready(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.markReady(
                ActorContext.requireUserId(), id, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/cancel")
    @Operation(
            summary = "Cancel an order",
            description = "Restaurant side. Free before acceptance, conditional after, "
                    + "impossible once the goods have left.")
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) ProcurementDtos.RejectRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.cancel(ActorContext.requireUserId(), id,
                request == null ? null : request.reason(), idempotencyKey));
    }

    @PostMapping("/requirements/{id}/find-suppliers")
    @Operation(
            summary = "Find suppliers for what is still needed",
            description = """
                    The recovery path after a supplier rejected, timed out or accepted less
                    (doc 15). Ranks alternatives against each item's **remaining** quantity,
                    so a supplier who can cover the shortfall qualifies even if they could
                    never have covered the original order.

                    An item nobody can currently serve comes back with a reason rather than
                    being omitted — an unmet need is shown, not silently dropped.

                    Returns options only. The restaurant chooses.
                    """)
    public ApiResponse<ProcurementDtos.AlternativesResponse> findSuppliers(@PathVariable Long id) {
        return ApiResponse.ok(alternatives.findSuppliers(ActorContext.requireUserId(), id));
    }
}
