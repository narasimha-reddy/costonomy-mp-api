package com.costonomy.mp.intent.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.intent.domain.IntentFulfilment;
import com.costonomy.mp.intent.service.IntentOrderService;
import com.costonomy.mp.intent.service.IntentService;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The restaurant's requests: build, send, follow, order from. §7, §13.
 *
 * <p>This replaces the cart endpoints for anything new. A request is the basket,
 * and the basket is per supplier — so there is no single "cart" resource here,
 * only the drafts an outlet is currently filling.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Requests")
public class IntentController {

    private final IntentService intents;
    private final IntentOrderService orders;

    // ── The basket ───────────────────────────────────────────────────────

    @GetMapping("/outlets/{outletId}/intent-drafts")
    @Operation(
            summary = "The outlet's unsent requests, one per supplier",
            description = """
                    This is the cart. A restaurant shopping across three suppliers has
                    three drafts, and the cart screen is a card each. The split happens
                    while they shop rather than at checkout, which is what keeps one
                    request to one order.
                    """)
    public ApiResponse<List<IntentDtos.IntentResponse>> drafts(@PathVariable Long outletId) {
        return ApiResponse.ok(intents.drafts(ActorContext.requireUserId(), outletId));
    }

    @PostMapping("/outlets/{outletId}/intent-items")
    @Operation(
            summary = "Add a supplier's pack to a request",
            description = """
                    Finds or opens the draft for that pack's supplier. Adding the same
                    pack again increases its quantity rather than creating a second
                    line. No price is stored: an intent records what is wanted, and what
                    it costs is the supplier's answer.
                    """)
    public ApiResponse<IntentDtos.IntentResponse> addItem(
            @PathVariable Long outletId,
            @Valid @RequestBody IntentDtos.AddItemRequest request) {
        return ApiResponse.ok(intents.addItem(ActorContext.requireUserId(), outletId, request));
    }

    @PatchMapping("/intent-items/{itemId}")
    @Operation(
            summary = "Change a line's quantity",
            description = "Zero removes the line, which is what a stepper at zero means.")
    public ApiResponse<IntentDtos.IntentResponse> updateItem(
            @PathVariable Long itemId,
            @Valid @RequestBody IntentDtos.UpdateItemRequest request) {
        return ApiResponse.ok(intents.updateItem(
                ActorContext.requireUserId(), itemId, request.quantity()));
    }

    @DeleteMapping("/intent-items/{itemId}")
    @Operation(summary = "Remove a line")
    public ApiResponse<IntentDtos.IntentResponse> removeItem(@PathVariable Long itemId) {
        return ApiResponse.ok(intents.removeItem(ActorContext.requireUserId(), itemId));
    }

    // ── Sending and following ────────────────────────────────────────────

    @PostMapping("/intents/{id}/send")
    @Operation(
            summary = "Send a request to its supplier",
            description = """
                    Moves the request from DRAFT to OPEN and freezes it — a supplier
                    pricing a request must not have it change underneath them. Still
                    non-financial: nothing is charged, reserved or authorised here.
                    """)
    public ApiResponse<IntentDtos.IntentResponse> send(
            @PathVariable Long id,
            @Valid @RequestBody IntentDtos.SendRequest request) {
        return ApiResponse.ok(intents.send(ActorContext.requireUserId(), id, request));
    }

    @GetMapping("/outlets/{outletId}/intents")
    @Operation(
            summary = "The outlet's sent requests",
            description = """
                    Filtered by **fulfilment** rather than status, because that is the
                    question being asked — what did I get, and what didn't I. Fulfilment
                    is derived from accepted against requested quantities, so it cannot
                    be filtered in SQL and is applied to the projection.
                    """)
    public ApiResponse<List<IntentDtos.IntentResponse>> list(
            @PathVariable Long outletId,
            @Parameter(description = "AWAITING, FULFILLED, PARTIALLY_FULFILLED or NOT_FULFILLED")
            @RequestParam(required = false) IntentFulfilment fulfilment) {
        return ApiResponse.ok(intents.list(ActorContext.requireUserId(), outletId, fulfilment));
    }

    @GetMapping("/intents/{id}")
    @Operation(
            summary = "One request, with the supplier's answer",
            description = """
                    Visible to the outlet that raised it and the store it went to. A
                    supplier's unsubmitted draft answer is withheld from the restaurant:
                    a half-typed offer is not a commitment.
                    """)
    public ApiResponse<IntentDtos.IntentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(intents.get(ActorContext.requireUserId(), id));
    }

    @PostMapping("/intents/{id}/cancel")
    @Operation(summary = "Withdraw a request")
    public ApiResponse<IntentDtos.IntentResponse> cancel(@PathVariable Long id) {
        return ApiResponse.ok(intents.cancel(ActorContext.requireUserId(), id));
    }

    @PostMapping("/intents/{id}/clone")
    @Operation(
            summary = "Copy a finished request into a new draft",
            description = """
                    How a request is repeated, and why ORDERED is terminal: an intent
                    records one conversation with one supplier, and reusing it would
                    overwrite what was asked and answered. Lines whose SKU the supplier
                    no longer lists are dropped.
                    """)
    public ApiResponse<IntentDtos.IntentResponse> clone(@PathVariable Long id) {
        return ApiResponse.ok(intents.clone(ActorContext.requireUserId(), id));
    }

    // ── Ordering ─────────────────────────────────────────────────────────

    @PostMapping("/intents/{id}/orders/preview")
    @Operation(
            summary = "What ordering this would cost",
            description = """
                    Changes nothing. Omit `lines` to price everything the supplier
                    offered; send them to order less. There is no price-change flow: an
                    acceptance is a quote with a deadline, and inside that deadline the
                    quoted price holds.
                    """)
    public ApiResponse<IntentDtos.OrderPreviewResponse> previewOrder(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) IntentDtos.CreateOrderRequest request) {
        return ApiResponse.ok(orders.preview(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/intents/{id}/orders")
    @Operation(
            summary = "Create the order",
            description = """
                    **The financial step.** Everything before it is a conversation; this
                    is where payment is arranged and the commission base is set. The
                    order is created already accepted — the supplier committed to these
                    quantities at these prices before any money moved — so it goes to
                    CONFIRMED once funded rather than waiting to be accepted again.

                    Quantities may be reduced against what was offered, never raised.
                    """)
    public ApiResponse<IntentDtos.CreateOrderResponse> createOrder(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) IntentDtos.CreateOrderRequest request,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(orders.create(
                ActorContext.requireUserId(), id, request, idempotencyKey));
    }
}
