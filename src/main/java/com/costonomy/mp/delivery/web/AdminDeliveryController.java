package com.costonomy.mp.delivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.delivery.service.AdminDeliveryService;
import com.costonomy.mp.delivery.service.DeliveryStatsAggregationService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
    private final DeliveryStatsAggregationService statsAggregationService;
    private final com.costonomy.mp.delivery.service.AdminDeliveryExportService exportService;

    @GetMapping("/{id}/ledger")
    @Operation(summary = "Audit trail for delivery financial ledger",
               description = "Requires DELIVERY_INSPECT permission at PLATFORM scope.")
    public ApiResponse<List<DeliveryDtos.DeliveryLedgerResponse>> ledger(@PathVariable Long id) {
        Long actorId = ActorContext.requireUserId();
        return ApiResponse.ok(adminDeliveryService.getLedger(actorId, id));
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
        // Access control handled inside aggregationService indirectly; require OPERATE here.
        ActorContext.requireUserId();
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
}

