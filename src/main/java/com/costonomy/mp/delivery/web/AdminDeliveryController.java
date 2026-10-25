package com.costonomy.mp.delivery.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.delivery.service.AdminDeliveryService;
import com.costonomy.mp.delivery.service.DeliveryStatsAggregationService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/deliveries")
@RequiredArgsConstructor
@Tag(name = "Operations - Delivery")
public class AdminDeliveryController {

    private final AdminDeliveryService adminDeliveryService;
    private final com.costonomy.mp.delivery.service.DeliveryResolutionService resolutionService;
    private final DeliveryStatsAggregationService statsAggregationService;
    private final com.costonomy.mp.delivery.service.AdminDeliveryExportService exportService;
    private final com.costonomy.mp.delivery.provider.pidge.PidgeApiClient pidgeApiClient;
    private final AccessControlService accessControl;
    private final PidgeProperties pidgeProperties;

    @GetMapping
    @Operation(summary = "List deliveries with pagination and filters",
               description = "Returns paginated deliveries (default: last 10, newest first) " +
                             "with joined supplier store, supplier org, and delivery provider details. " +
                             "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<DeliveryDtos.PagedResponse<DeliveryDtos.AdminDeliverySummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) Long supplierStoreId) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.listDeliveries(actorId, page, size, status, providerCode, supplierStoreId));
    }

    @GetMapping("/late")
    @Operation(summary = "List deliveries with missed ETA",
               description = "Returns paginated deliveries with missed ETAs sorted by minutes overdue. " +
                             "If liveOnly=true (default: true), only lists active deliveries currently in transit that have passed their estimated arrival. " +
                             "If liveOnly=false, includes completed deliveries that breached quoted ETA. " +
                             "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<DeliveryDtos.PagedResponse<DeliveryDtos.AdminDeliverySummaryResponse>> listLate(
            @RequestParam(defaultValue = "true") boolean liveOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.listLateDeliveries(actorId, liveOnly, page, size));
    }

    @GetMapping("/{id}/ledger")
    @Operation(summary = "Audit trail for delivery financial ledger",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<List<DeliveryDtos.DeliveryLedgerResponse>> ledger(@PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getLedger(actorId, id));
    }

    @GetMapping("/{id}/inspect")
    @Operation(summary = "Operator inspection view for a supplier delivery",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<FoodDeliveryDtos.SupplierDeliveryInspectionResponse> inspectSupplierDelivery(@PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.inspectSupplierDelivery(actorId, id));
    }

    @GetMapping("/food-deliveries/{id}/inspect")
    @Operation(summary = "Operator inspection view for a food delivery",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<FoodDeliveryDtos.FoodDeliveryInspectionResponse> inspectFoodDelivery(@PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.inspectFoodDelivery(actorId, id));
    }

    @GetMapping("/operational-alerts")
    @Operation(summary = "Operational alerts for stuck deliveries and carrier health",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<FoodDeliveryDtos.OperationalAlertsResponse> operationalAlerts() {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getOperationalAlerts(actorId));
    }

    @PostMapping("/{id}/force-waterfall")
    @Operation(summary = "Manually trigger carrier waterfall escalation",
               description = "Requires DELIVERY_OPERATE permission at PLATFORM scope.")
    public ApiResponse<DeliveryDtos.DeliveryResponse> forceWaterfall(
            @PathVariable Long id,
            @RequestBody(required = false) DeliveryDtos.ReassignDeliveryRequest request) {
        Long actorId = ActorContext.requireUserId();
        String reason = request != null && request.reason() != null
                ? request.reason()
                : "Operations manual escalation";
        return ApiResponse.ok(adminDeliveryService.forceWaterfall(actorId, id, reason));
    }

    @GetMapping("/exceptions")
    @Operation(summary = "Orders that look finished or under way while delivery or money work is unresolved",
               description = "Ready with no courier, on the road but failed, courier payments unresolved, and supplier shares "
                       + "uncollected for weeks. Bounded at 50 per kind, oldest first. Requires DELIVERY_INSPECT.")
    public ApiResponse<List<DeliveryDtos.JourneyException>> exceptions() {
        return ApiResponse.ok(adminDeliveryService.journeyExceptions(ActorContext.requireUserId()));
    }

    public record ClosePickupRequest(@jakarta.validation.constraints.NotBlank(message = "Say why") String reason) {
    }

    @PostMapping("/orders/{orderId}/close-pickup")
    @Operation(summary = "Cancel and refund a pickup order the restaurant never collected",
               description = "A pickup order has no delivery to resolve and cannot be cancelled by either side once ready. "
                       + "No timeout ends it automatically; this is the operator's exit. Reason required, audited, idempotent. "
                       + "Requires DELIVERY_OPERATE permission at PLATFORM scope.")
    public ApiResponse<Map<String, Object>> closePickup(@PathVariable Long orderId,
                                                        @jakarta.validation.Valid @RequestBody ClosePickupRequest request) {
        resolutionService.closeUncollectedPickup(ActorContext.requireUserId(), orderId, request.reason());
        return ApiResponse.ok(Map.of("supplierOrderId", orderId, "status", "CANCELLED"));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Close or reconcile a delivery that cannot finish by itself",
               description = "Cancel with refund, mark delivered, associate discovered consignment, or resume booking. Requires "
                       + "DELIVERY_OPERATE permission at PLATFORM scope, and a reason.")
    public ApiResponse<DeliveryDtos.DeliveryResponse> resolve(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody DeliveryDtos.ResolveDeliveryRequest request) {
        Long actorId = ActorContext.requireUserId();
        com.costonomy.mp.delivery.service.DeliveryResolutionService.Outcome outcome;
        try {
            outcome = com.costonomy.mp.delivery.service.DeliveryResolutionService.Outcome.valueOf(request.outcome());
        } catch (IllegalArgumentException ex) {
            throw new com.costonomy.mp.common.error.BusinessException(
                    com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                    "The outcome must be CANCEL_AND_REFUND, MARK_DELIVERED, ASSOCIATE_CONSIGNMENT, or RESUME_BOOKING.");
        }
        return ApiResponse.ok(resolutionService.resolve(actorId, id, outcome, request.reason(), request.providerDeliveryId()));
    }

    // -----------------------------------------------------------------------
    // Provider reliability stats
    // -----------------------------------------------------------------------

    @GetMapping("/providers/stats")
    @Operation(summary = "Rolling 30-day reliability stats for all providers",
               description = "Returns one row per (provider, day) for the last 30 days. " +
                             "Requires DELIVERY_INSPECT at PLATFORM scope.")
    public ApiResponse<List<DeliveryDtos.ProviderStatsResponse>> providerStats() {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getLast30DaysStats(actorId));
    }

    @GetMapping("/providers/{code}/stats")
    @Operation(summary = "Full daily stats history for one provider",
               description = "Requires DELIVERY_INSPECT at PLATFORM scope.")
    public ApiResponse<List<DeliveryDtos.ProviderStatsResponse>> providerStatsByCode(
            @PathVariable String code) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getProviderStats(actorId, code));
    }

    @PostMapping("/providers/stats/aggregate")
    @Operation(summary = "Manually trigger stats aggregation for a given date",
               description = "Idempotent — safe to re-run after a data backfill. " +
                             "Defaults to yesterday if no date supplied. " +
                             "Requires DELIVERY_OPERATE at PLATFORM scope.")
    public ApiResponse<Map<String, Object>> triggerAggregation(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);
        LocalDate target = date != null ? date : LocalDate.now().minusDays(1);
        int written = statsAggregationService.aggregate(target);
        return ApiResponse.ok(Map.of("windowDate", target.toString(), "rowsWritten", written));
    }

    // -----------------------------------------------------------------------
    // Provider latency & cost metrics (2-hour rolling windows)
    // -----------------------------------------------------------------------

    @GetMapping("/providers/metrics")
    @Operation(summary = "Recent 2-hour latency and cost metrics across providers",
               description = "Returns aggregated metrics for the last N days (defaults to 7). " +
                             "Requires DELIVERY_INSPECT at PLATFORM scope.")
    public ApiResponse<List<DeliveryDtos.ProviderMetricsResponse>> providerMetrics(
            @RequestParam(defaultValue = "7") int days) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getLastMetrics(actorId, days));
    }

    @GetMapping("/providers/{code}/metrics")
    @Operation(summary = "Full 2-hour metrics history for one provider",
               description = "Requires DELIVERY_INSPECT at PLATFORM scope.")
    public ApiResponse<List<DeliveryDtos.ProviderMetricsResponse>> providerMetricsByCode(
            @PathVariable String code) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getProviderMetrics(actorId, code));
    }

    // -----------------------------------------------------------------------
    // Bulk delivery export (CSV & JSON)
    // -----------------------------------------------------------------------

    @GetMapping(value = "/export")
    @Operation(summary = "Bulk export deliveries as CSV or JSON",
               description = "Streams delivery records matching optional filters (since date, status). " +
                             "Format query param accepts 'csv' or 'json' (default: json). " +
                             "Requires DELIVERY_INSPECT at PLATFORM scope.")
    public Object exportDeliveries(
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1000") int limit,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        Long actorId = ActorContext.requireUserId();
        List<com.costonomy.mp.delivery.web.dto.DeliveryExportRow> rows = exportService.fetchDeliveries(actorId, since, status, limit);

        if ("csv".equalsIgnoreCase(format)) {
            response.setContentType("text/csv");
            response.setHeader("Content-Disposition", "attachment; filename=\"deliveries_export.csv\"");
            com.costonomy.mp.delivery.service.AdminDeliveryExportService.writeCsv(rows, response.getWriter());
            return null;
        }

        return ApiResponse.ok(rows);
    }

    // -----------------------------------------------------------------------
    // Pidge Staging Sandbox Simulation
    // -----------------------------------------------------------------------

    @GetMapping("/pidge/sandbox/status")
    @Operation(summary = "Pidge Sandbox: simulate status retrieval",
               description = "Calls Pidge staging dummy GET endpoint. Allowed statuses: cancelled, pending, " +
                             "fulfilled|registered, fulfilled|out for pickup, fulfilled|reached pickup, " +
                             "fulfilled|picked up, fulfilled|ofd, fulfilled|reached delivery, fulfilled|undelivered, " +
                             "fulfilled|delivered, fulfilled|rto out for delivery, fulfilled|rto delivered. " +
                             "Requires DELIVERY_OPERATE at PLATFORM scope and non-production/staging sandbox.")
    public ApiResponse<Object> simulatePidgeStatus(
            @RequestParam String pidgeDeliveryId,
            @RequestParam String dummyStatus) {
        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);
        requireSandboxEnvironment();
        return ApiResponse.ok(pidgeApiClient.simulateOrderStatus(pidgeDeliveryId, dummyStatus));
    }

    @PostMapping("/pidge/sandbox/webhook")
    @Operation(summary = "Pidge Sandbox: trigger simulated webhook",
               description = "Calls Pidge staging dummy webhook trigger endpoint to dispatch a simulated callback event. " +
                             "Requires DELIVERY_OPERATE at PLATFORM scope and non-production/staging sandbox.")
    public ApiResponse<Object> triggerPidgeWebhook(
            @RequestParam String pidgeDeliveryId,
            @RequestParam String dummyStatus) {
        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);
        requireSandboxEnvironment();
        return ApiResponse.ok(pidgeApiClient.triggerSandboxWebhook(pidgeDeliveryId, dummyStatus));
    }

    private void requireSandboxEnvironment() {
        if (!pidgeProperties.isSandbox()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Pidge sandbox simulation is only permitted in non-production/staging environments.");
        }
    }
}


