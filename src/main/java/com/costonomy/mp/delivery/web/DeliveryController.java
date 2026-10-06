package com.costonomy.mp.delivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.delivery.service.OutletDeliveryRadarService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Delivery. Doc 04 §14, doc 06.
 *
 * <p>Nothing here exposes a provider, a quote or a bid. Doc 06 §4 and §10: the
 * restaurant sees one fee and the state of their delivery. There is no endpoint
 * that returns {@code delivery_quote} rows, and that is deliberate.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Delivery")
public class DeliveryController {

    private final DeliveryService deliveries;
    private final IdempotencyService idempotency;
    private final OutletDeliveryRadarService radarService;

    @PostMapping("/supplier-orders/{orderId}/delivery")
    @Operation(
            summary = "Arrange delivery for an order that is ready",
            description = """
                    Quotes every available partner, picks the cheapest that meets the
                    required ETA, and books it. For a supplier who delivers themselves
                    there is no partner and no tracking — they carry it and report
                    their own progress.

                    Idempotent on the order: asking twice returns the delivery that
                    already exists rather than sending a second driver.
                    """)
    public ApiResponse<DeliveryDtos.DeliveryResponse> request(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) DeliveryDtos.RequestDeliveryRequest body) {

        Long actorId = ActorContext.requireUserId();
        var request = body == null
                ? new DeliveryDtos.RequestDeliveryRequest(null, null) : body;

        return ApiResponse.ok(idempotency.execute(actorId, "delivery.request", idempotencyKey,
                Map.of("orderId", orderId),
                DeliveryDtos.DeliveryResponse.class,
                () -> deliveries.request(actorId, orderId, request)));
    }

    @GetMapping("/supplier-orders/{orderId}/delivery")
    @Operation(summary = "This order's delivery")
    public ApiResponse<DeliveryDtos.DeliveryResponse> forOrder(@PathVariable Long orderId) {
        return ApiResponse.ok(deliveries.forOrder(ActorContext.requireUserId(), orderId));
    }

    @GetMapping("/deliveries/{id}")
    @Operation(
            summary = "Track a delivery",
            description = """
                    Before a driver is assigned there is no driver and no position, and the
                    response says so rather than inventing either. After assignment it
                    carries the driver, the ETA and the last known position — with
                    `locationStale` set when that position is older than the freshness
                    threshold, so the app shows a stale state instead of an old pin.
                    """)
    public ApiResponse<DeliveryDtos.DeliveryResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(deliveries.get(ActorContext.requireUserId(), id));
    }

    @GetMapping("/deliveries/{id}/events")
    @Operation(summary = "The delivery timeline",
            description = "Applied events only, oldest first.")
    public ApiResponse<List<DeliveryDtos.EventResponse>> events(@PathVariable Long id) {
        return ApiResponse.ok(deliveries.timelineFor(ActorContext.requireUserId(), id));
    }

    @PostMapping("/deliveries/{id}/cancel")
    @Operation(summary = "Cancel a delivery",
            description = "Refused once the goods have been picked up — from there the "
                    + "path is a return or a dispute, not a cancellation.")
    public ApiResponse<DeliveryDtos.DeliveryResponse> cancel(
            @PathVariable Long id,
            @Valid @RequestBody DeliveryDtos.CancelDeliveryRequest body) {
        return ApiResponse.ok(deliveries.cancel(ActorContext.requireUserId(), id, body.reason()));
    }

    @PostMapping("/deliveries/{id}/reassign")
    @Operation(
            summary = "Move this consignment to another partner",
            description = """
                    Keeps the same delivery. The partner that just failed is excluded, an
                    attempt is recorded, and the restaurant carries on watching the delivery
                    they were already watching — doc 06 §7 forbids a second logical delivery.
                    """)
    public ApiResponse<DeliveryDtos.DeliveryResponse> reassign(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) DeliveryDtos.ReassignDeliveryRequest body) {

        Long actorId = ActorContext.requireUserId();
        String reason = body == null ? null : body.reason();

        return ApiResponse.ok(idempotency.execute(actorId, "delivery.reassign", idempotencyKey,
                Map.of("deliveryId", id),
                DeliveryDtos.DeliveryResponse.class,
                () -> deliveries.reassign(actorId, id, reason)));
    }

    @PostMapping("/deliveries/{id}/switch-to-own")
    @Operation(summary = "Deliver it yourself, because no delivery partner was found",
            description = "Supplier only, once the offer has been made after the automatic retries. The same delivery "
                    + "becomes the supplier's own; the delivery charge the buyer paid is unchanged (D-151).")
    public ApiResponse<DeliveryDtos.DeliveryResponse> switchToOwn(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(idempotency.execute(actorId, "delivery.switchToOwn", idempotencyKey,
                Map.of("deliveryId", id),
                DeliveryDtos.DeliveryResponse.class,
                () -> deliveries.switchToOwn(actorId, id)));
    }

    // ── Supplier own delivery ────────────────────────────────────────────

    @PostMapping("/deliveries/{id}/dispatched")
    @Operation(summary = "The supplier has set off with it",
            description = "Own delivery only. On a partner delivery, progress comes from "
                    + "the partner — a supplier cannot report a pickup someone else made.")
    public ApiResponse<DeliveryDtos.DeliveryResponse> dispatched(@PathVariable Long id) {
        return ApiResponse.ok(deliveries.supplierReports(
                ActorContext.requireUserId(), id, DeliveryStatus.PICKED_UP));
    }

    @PostMapping("/deliveries/{id}/delivered")
    @Operation(summary = "The supplier has delivered it", description = "Own delivery only.")
    public ApiResponse<DeliveryDtos.DeliveryResponse> delivered(@PathVariable Long id) {
        return ApiResponse.ok(deliveries.supplierReports(
                ActorContext.requireUserId(), id, DeliveryStatus.DELIVERED));
    }

    // ── Outlet Delivery Radar & Operational Situation ───────────────────

    @GetMapping("/outlets/{outletId}/deliveries/radar")
    @Operation(
            summary = "Situational delivery radar for an outlet",
            description = """
                    Ranks active incoming deliveries by physical arrival urgency.
                    Directly answers:
                    - Which order is approaching the kitchen first?
                    - Is the supplier on schedule or delayed?
                    - Did the driver call / is there a problem?
                    - Which orders need to be checked in, met at the dock, or escalated?
                    """)
    public ApiResponse<DeliveryDtos.OutletDeliveryRadarResponse> radar(
            @PathVariable Long outletId,
            @RequestParam(value = "action", required = false) DeliveryDtos.KitchenAction action,
            @RequestParam(value = "stage", required = false) DeliveryDtos.ArrivalStage stage,
            @RequestParam(value = "scheduleStatus", required = false) DeliveryDtos.ScheduleStatus scheduleStatus) {
        return ApiResponse.ok(radarService.getRadar(
                ActorContext.requireUserId(), outletId, action, stage, scheduleStatus));
    }

    @GetMapping("/outlets/{outletId}/deliveries")
    @Operation(
            summary = "Paginated list of deliveries for an outlet",
            description = "Lists outlet deliveries with default 10 per page, newest first.")
    public ApiResponse<DeliveryDtos.PagedResponse<DeliveryDtos.OutletDeliveryRadarItemResponse>> outletDeliveries(
            @PathVariable Long outletId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(radarService.listDeliveries(
                ActorContext.requireUserId(), outletId, page, size, status));
    }
}
