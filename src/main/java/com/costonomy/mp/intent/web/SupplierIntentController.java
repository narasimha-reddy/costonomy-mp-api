package com.costonomy.mp.intent.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.service.IntentAcceptanceService;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The supplier's side: what has been asked of this store, and answering it.
 * §10, §14.
 *
 * <p>Requests sit <b>above</b> new orders on the supplier's home screen, because
 * a request is the one thing on that screen with a counterparty waiting on it. An
 * order created from an accepted request arrives already confirmed and needs no
 * decision at all.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Requests (supplier)")
public class SupplierIntentController {

    private final IntentAcceptanceService acceptances;

    @GetMapping("/supplier-stores/{storeId}/intents/carousel")
    @Operation(
            summary = "The newest requests worth looking at",
            description = """
                    Capped at ten for the home carousel. Open and just-answered
                    requests, newest first; the list endpoint is where a supplier works
                    through everything.
                    """)
    public ApiResponse<List<IntentDtos.IntentResponse>> carousel(@PathVariable Long storeId) {
        return ApiResponse.ok(acceptances.carousel(ActorContext.requireUserId(), storeId));
    }

    @GetMapping("/supplier-stores/{storeId}/intents")
    @Operation(
            summary = "Every request this store has received",
            description = """
                    Drafts never appear: a basket the restaurant is still filling has not
                    been sent to anybody.
                    """)
    public ApiResponse<List<IntentDtos.IntentResponse>> list(
            @PathVariable Long storeId,
            @Parameter(description = "OPEN, RESPONSES_RECEIVED, ORDERED, EXPIRED, "
                    + "ORDER_CREATION_EXPIRED or CANCELLED")
            @RequestParam(required = false) IntentStatus status) {
        return ApiResponse.ok(acceptances.list(ActorContext.requireUserId(), storeId, status));
    }

    @PostMapping("/intents/{id}/respond")
    @Operation(
            summary = "Say what this store will supply",
            description = """
                    **Every line must be answered.** Offer zero to decline one. An
                    omitted line is rejected rather than read as a zero: a client
                    dropping a row would otherwise become a refusal the supplier never
                    made, and the restaurant would be told a product was unavailable
                    when nobody had said so.

                    Quantities may be reduced, never raised above what was asked for.
                    **No prices are sent** — each line is priced from this store's live
                    catalogue offer. Change a price by superseding the offer, not by
                    answering a request differently.

                    Answering in full, answering short and declining outright need
                    ORDER_ACCEPT, ORDER_PARTIAL_ACCEPT and ORDER_REJECT respectively.
                    """)
    public ApiResponse<IntentDtos.IntentResponse> respond(
            @PathVariable Long id,
            @Valid @RequestBody IntentDtos.RespondRequest request,
            @Parameter(description = "Client-generated key, required for this operation")
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ApiResponse.ok(acceptances.respond(
                ActorContext.requireUserId(), id, request, idempotencyKey));
    }
}
