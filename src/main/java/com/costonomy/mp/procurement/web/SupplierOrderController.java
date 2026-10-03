package com.costonomy.mp.procurement.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.procurement.service.AlternativeSourcingService;
import com.costonomy.mp.procurement.service.SupplierOrderService;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.domain.CancelledBy;
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

    // No accept, partial-accept or reject. D-091: the supplier committed on the
    // request, so an order arrives agreed and paid for. What they can still do is
    // prepare it, move it, or cancel it -- and a cancellation refunds, which is
    // what makes it different from the rejection this replaced.

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
            description = "Restaurant side. Impossible once the goods have left.")
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) ProcurementDtos.RejectRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.cancel(ActorContext.requireUserId(), id,
                request == null ? null : request.reason(),
                CancelledBy.RESTAURANT, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/supplier-cancel")
    @Operation(
            summary = "Cancel an order, as the supplier",
            description = """
                    The supplier's way out of an order they can no longer fulfil, and the
                    replacement for the rejection D-091 removed.

                    It is a cancellation rather than a rejection because the money has
                    already moved: the supplier agreed on the request and the restaurant
                    paid against that answer, so backing out refunds. The order records
                    `cancelledBy = SUPPLIER`, which is what a reliability figure reads.

                    Impossible once the goods have left — the path then is return or
                    dispute (doc 01 §13).
                    """)
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> supplierCancel(
            @PathVariable Long id,
            @RequestBody(required = false) ProcurementDtos.RejectRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.cancel(ActorContext.requireUserId(), id,
                request == null ? null : request.reason(),
                CancelledBy.SUPPLIER, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/out-for-delivery")
    @Operation(
            summary = "Mark out for delivery",
            description = """
                    Only when the supplier is carrying the order themselves
                    (`SUPPLIER_DELIVERY`). Under `COSTONOMY_DELIVERY` the courier's events
                    move the order and this is refused — §23A.38, a supplier cannot claim
                    movement on a courier's behalf. Under `PICKUP` nothing is delivered at
                    all.
                    """)
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> outForDelivery(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.markOutForDelivery(
                ActorContext.requireUserId(), id, idempotencyKey));
    }

    @PostMapping("/supplier-orders/{id}/delivered")
    @Operation(
            summary = "Mark delivered",
            description = "Supplier-carried orders only, for the same reason as "
                    + "out-for-delivery. The restaurant then confirms what arrived.")
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> delivered(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(supplierOrders.markDelivered(
                ActorContext.requireUserId(), id, idempotencyKey));
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
