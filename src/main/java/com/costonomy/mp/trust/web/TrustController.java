package com.costonomy.mp.trust.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.trust.service.DisputeService;
import com.costonomy.mp.trust.service.RatingService;
import com.costonomy.mp.trust.service.ReceivingService;
import com.costonomy.mp.trust.web.dto.TrustDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Receiving, disputes and ratings. Doc 04 §15–17.
 *
 * <p>The three things that happen after goods arrive. They are independent of each
 * other by design: a delivery can be received without a dispute, disputed without
 * a rating, and rated whatever the dispute concluded.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Trust")
public class TrustController {

    private final ReceivingService receiving;
    private final DisputeService disputes;
    private final RatingService ratings;
    private final IdempotencyService idempotency;

    // ── Receiving ────────────────────────────────────────────────────────

    @PostMapping("/supplier-orders/{orderId}/receive")
    @Operation(
            summary = "Check a delivery in",
            description = """
                    Every line must be answered, and `received + damaged + missing` must add
                    up to what the supplier accepted — a mismatch is refused with the
                    arithmetic, because the usual cause is a typo.

                    Recorded alongside the order, never over it: the accepted quantities
                    stay as committed, and what arrived is added. Completes the order.
                    """)
    public ApiResponse<TrustDtos.ReceivingResponse> receive(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TrustDtos.ReceiveRequest request) {

        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(idempotency.execute(actorId, "order.receive", idempotencyKey,
                Map.of("orderId", orderId, "items", request.items()),
                TrustDtos.ReceivingResponse.class,
                () -> receiving.receive(actorId, orderId, request)));
    }

    @GetMapping("/supplier-orders/{orderId}/receiving")
    @Operation(summary = "What was received against this order",
            description = "Visible to both parties — the supplier is being measured by it.")
    public ApiResponse<TrustDtos.ReceivingResponse> receivingFor(@PathVariable Long orderId) {
        return ApiResponse.ok(receiving.forOrder(ActorContext.requireUserId(), orderId));
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    @PostMapping("/supplier-orders/{orderId}/disputes")
    @Operation(
            summary = "Raise a dispute",
            description = """
                    Does not change the order. It stays DELIVERED or COMPLETED throughout,
                    and the response carries that status so the app can say so.

                    More than one dispute per order is allowed: a delivery can be both short
                    and damaged, and one dispute per order would force a choice about which
                    problem to report.
                    """)
    public ApiResponse<TrustDtos.DisputeResponse> raiseDispute(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TrustDtos.CreateDisputeRequest request) {

        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(idempotency.execute(actorId, "dispute.create", idempotencyKey,
                Map.of("orderId", orderId, "category", request.category()),
                TrustDtos.DisputeResponse.class,
                () -> disputes.raise(actorId, orderId, request)));
    }

    @GetMapping("/supplier-orders/{orderId}/disputes")
    @Operation(summary = "Disputes raised against this order")
    public ApiResponse<List<TrustDtos.DisputeResponse>> disputesFor(@PathVariable Long orderId) {
        return ApiResponse.ok(disputes.forOrder(ActorContext.requireUserId(), orderId));
    }

    @GetMapping("/disputes/{id}")
    @Operation(summary = "One dispute, with its thread",
            description = "Internal operations notes are never included.")
    public ApiResponse<TrustDtos.DisputeResponse> dispute(@PathVariable Long id) {
        return ApiResponse.ok(disputes.get(ActorContext.requireUserId(), id));
    }

    @PostMapping("/disputes/{id}/response")
    @Operation(summary = "Answer a dispute",
            description = "Supplier only. A proposed resolution is recorded, not applied — "
                    + "only the restaurant closes a dispute.")
    public ApiResponse<TrustDtos.DisputeResponse> respond(
            @PathVariable Long id,
            @Valid @RequestBody TrustDtos.RespondToDisputeRequest request) {
        return ApiResponse.ok(disputes.respond(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/disputes/{id}/messages")
    @Operation(summary = "Add to the conversation")
    public ApiResponse<TrustDtos.DisputeResponse> comment(
            @PathVariable Long id,
            @Valid @RequestBody TrustDtos.DisputeMessageRequest request) {
        return ApiResponse.ok(disputes.comment(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/disputes/{id}/resolve")
    @Operation(summary = "Close a dispute as resolved",
            description = "Restaurant only. Records what the two sides agreed; no money "
                    + "moves here — Mandi records disputes, it does not adjudicate them.")
    public ApiResponse<TrustDtos.DisputeResponse> resolve(
            @PathVariable Long id,
            @Valid @RequestBody TrustDtos.ResolveDisputeRequest request) {
        return ApiResponse.ok(disputes.resolve(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/disputes/{id}/reject")
    @Operation(summary = "Reject a dispute", description = "Supplier only, with a reason.")
    public ApiResponse<TrustDtos.DisputeResponse> rejectDispute(
            @PathVariable Long id,
            @Valid @RequestBody TrustDtos.ResolveDisputeRequest request) {
        return ApiResponse.ok(disputes.reject(ActorContext.requireUserId(), id, request));
    }

    // ── Ratings ──────────────────────────────────────────────────────────

    @PostMapping("/supplier-orders/{orderId}/rating")
    @Operation(summary = "Rate a completed order",
            description = "One per order. A repeat returns the original rather than "
                    + "erroring — the honest answer to \"did it go through?\" is the rating.")
    public ApiResponse<TrustDtos.RatingResponse> rate(
            @PathVariable Long orderId,
            @Valid @RequestBody TrustDtos.CreateRatingRequest request) {
        return ApiResponse.ok(ratings.rate(ActorContext.requireUserId(), orderId, request));
    }

    @GetMapping("/supplier-orders/{orderId}/rating")
    @Operation(summary = "This order's rating")
    public ApiResponse<TrustDtos.RatingResponse> ratingFor(@PathVariable Long orderId) {
        return ApiResponse.ok(ratings.forOrder(ActorContext.requireUserId(), orderId));
    }

    @GetMapping("/supplier-stores/{storeId}/ratings")
    @Operation(summary = "A store's public rating",
            description = """
                    Averages are computed from published ratings only, so a hidden rating
                    leaves the average. A store nobody has rated has no average — null,
                    never a default of three.
                    """)
    public ApiResponse<TrustDtos.RatingSummaryResponse> storeRatings(@PathVariable Long storeId) {
        return ApiResponse.ok(ratings.summaryFor(storeId));
    }

    @PostMapping("/internal/ratings/{id}/moderate")
    @Operation(summary = "Hide or restore a rating (internal)",
            description = "Requires RATING_MODERATE at platform scope. Auditable, and the "
                    + "reason is required — an unexplained removal reads as censorship.")
    public ApiResponse<TrustDtos.RatingResponse> moderate(
            @PathVariable Long id,
            @Valid @RequestBody TrustDtos.ModerateRatingRequest request) {
        return ApiResponse.ok(ratings.moderate(ActorContext.requireUserId(), id, request));
    }
}
