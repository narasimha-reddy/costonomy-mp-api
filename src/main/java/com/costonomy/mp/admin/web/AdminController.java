package com.costonomy.mp.admin.web;

import com.costonomy.mp.admin.service.*;
import com.costonomy.mp.admin.web.dto.AdminDtos;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Operations. Doc 04 §19, doc 09 §12–13, §17.
 *
 * <p><b>A separate API, not a privileged view of the tenant one.</b> Doc 09 §17:
 * "design APIs so a future Operations web app can consume them without changing
 * domain rules". Everything here is gated on an INTERNAL permission at PLATFORM
 * scope — no tenant permission appears anywhere in the admin module, and V17 took
 * the borrowed ones off the operations roles so an operator cannot quietly arrive
 * through a restaurant's endpoints instead.
 *
 * <p><b>Reads and writes are separately permissioned</b> (doc 09 §13), so a support
 * user can be given the whole of the inspection surface and none of the mutations.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Operations")
public class AdminController {

    private final AdminQueryService queries;
    private final AdminModerationService moderation;
    private final AdminConfigService config;
    private final OperationsDashboardService dashboard;

    // ── Suppliers ────────────────────────────────────────────────────────

    @GetMapping("/suppliers")
    @Operation(summary = "Search suppliers", description = "By name or GSTIN, and by "
            + "lifecycle status. Requires SUPPLIER_INSPECT.")
    public ApiResponse<List<AdminDtos.SupplierSummary>> suppliers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queries.searchSuppliers(
                ActorContext.requireUserId(), query, status, limit));
    }

    @GetMapping("/suppliers/{id}")
    @Operation(summary = "One supplier, with its stores and their ratings")
    public ApiResponse<AdminDtos.SupplierDetail> supplier(@PathVariable Long id) {
        return ApiResponse.ok(queries.supplier(ActorContext.requireUserId(), id));
    }

    @PostMapping("/suppliers/{id}/suspend")
    @Operation(
            summary = "Stop a supplier trading",
            description = """
                    Requires SUPPLIER_SUSPEND and a reason, which is audited.

                    Existing orders are untouched: a supplier suspended today still owes the
                    deliveries they accepted yesterday, and cancelling those would punish
                    the restaurants rather than the supplier.
                    """)
    public ApiResponse<Map<String, Object>> suspend(
            @PathVariable Long id,
            @Valid @RequestBody AdminDtos.SuspendSupplierRequest request) {
        moderation.suspendSupplier(ActorContext.requireUserId(), id, request.reason());
        return ApiResponse.ok(Map.of("suspended", true));
    }

    @PostMapping("/suppliers/{id}/reactivate")
    @Operation(summary = "Let a suspended supplier trade again")
    public ApiResponse<Map<String, Object>> reactivate(
            @PathVariable Long id,
            @Valid @RequestBody AdminDtos.SuspendSupplierRequest request) {
        moderation.reactivateSupplier(ActorContext.requireUserId(), id, request.reason());
        return ApiResponse.ok(Map.of("reactivated", true));
    }

    // ── Catalog ──────────────────────────────────────────────────────────

    @PostMapping("/catalog/skus/{id}/disable")
    @Operation(
            summary = "Disable a supplier SKU",
            description = """
                    Requires CATALOG_MODERATE. Disabled, not deleted, and its live offer is
                    superseded rather than removed — an order placed last week was placed at
                    a price, and deleting the SKU would make that order unreconstructable.
                    """)
    public ApiResponse<Map<String, Object>> disableSku(
            @PathVariable Long id,
            @Valid @RequestBody AdminDtos.SuspendSupplierRequest request) {
        moderation.disableSku(ActorContext.requireUserId(), id, request.reason());
        return ApiResponse.ok(Map.of("disabled", true));
    }

    @PatchMapping("/catalog/products/{id}")
    @Operation(summary = "Correct a canonical product's name",
            description = "Canonical products are platform-owned and are the axis every "
                    + "comparison turns on, so a typo costs every supplier mapped to it.")
    public ApiResponse<Map<String, Object>> renameProduct(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        moderation.renameCanonicalProduct(ActorContext.requireUserId(), id,
                request.get("name"), request.getOrDefault("reason", "Catalog correction"));
        return ApiResponse.ok(Map.of("renamed", true));
    }

    // ── Orders ───────────────────────────────────────────────────────────

    @GetMapping("/orders")
    @Operation(summary = "Search orders across the marketplace",
            description = "By order number, status, outlet or store. Requires ORDER_INSPECT.")
    public ApiResponse<List<AdminDtos.OrderSummary>> orders(
            @RequestParam(required = false) String orderNumber,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long outletId,
            @RequestParam(required = false) Long supplierStoreId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queries.searchOrders(ActorContext.requireUserId(),
                orderNumber, status, outletId, supplierStoreId, limit));
    }

    @GetMapping("/orders/{id}/timeline")
    @Operation(
            summary = "Everything that happened to one order",
            description = """
                    State changes, courier events — including the ones correctly ignored as
                    duplicates or out of order — and disputes, merged in time order, with the
                    payment and delivery records attached.

                    The single most useful screen an operator has: "where is my order" is
                    usually answered by the ordering.
                    """)
    public ApiResponse<AdminDtos.OrderTimeline> timeline(@PathVariable Long id) {
        return ApiResponse.ok(queries.orderTimeline(ActorContext.requireUserId(), id));
    }

    @GetMapping("/orders/{id}/payment")
    @Operation(summary = "An order's payment and reconciliation state",
            description = "Requires PAYMENT_INSPECT, which does not imply PAYMENT_RECONCILE.")
    public ApiResponse<AdminDtos.PaymentDetail> payment(@PathVariable Long id) {
        return ApiResponse.ok(queries.payment(ActorContext.requireUserId(), id));
    }

    @GetMapping("/orders/{id}/delivery")
    @Operation(
            summary = "An order's delivery, with every attempt and quote",
            description = """
                    Requires DELIVERY_INSPECT, which does not imply DELIVERY_OPERATE.

                    This is the only place provider quotes and identities are exposed: doc 06
                    §4 and §10 keep them from restaurants, not from operations.
                    """)
    public ApiResponse<AdminDtos.DeliveryDetail> delivery(@PathVariable Long id) {
        return ApiResponse.ok(queries.delivery(ActorContext.requireUserId(), id));
    }

    // ── Credit ───────────────────────────────────────────────────────────

    @GetMapping("/credit/exposure")
    @Operation(summary = "Credit exposure across the marketplace",
            description = "Worst overdue first. Requires CREDIT_AUDIT.")
    public ApiResponse<List<AdminDtos.CreditExposureSummary>> creditExposure(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queries.creditExposure(
                ActorContext.requireUserId(), status, limit));
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    @GetMapping("/disputes")
    @Operation(summary = "Search disputes", description = "Requires DISPUTE_INSPECT.")
    public ApiResponse<List<AdminDtos.DisputeSummary>> disputes(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queries.searchDisputes(
                ActorContext.requireUserId(), status, category, limit));
    }

    @PostMapping("/disputes/{id}/resolve")
    @Operation(
            summary = "Close a dispute the parties could not close themselves",
            description = """
                    Requires DISPUTE_MODERATE. Records an outcome; moves no money — Mandi
                    does not adjudicate between a restaurant and a supplier (doc 01 §23).

                    An `internalNote` is stored as an operations-only message that neither
                    party sees; the `resolution` is what they read.
                    """)
    public ApiResponse<Map<String, Object>> resolveDispute(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        moderation.resolveDispute(ActorContext.requireUserId(), id,
                request.get("resolutionType"), request.get("resolution"),
                request.get("internalNote"));
        return ApiResponse.ok(Map.of("resolved", true));
    }

    // ── Audit ────────────────────────────────────────────────────────────

    @GetMapping("/audit")
    @Operation(
            summary = "Search the audit trail",
            description = """
                    By actor, entity, action or date — the four questions an investigation
                    asks. Requires AUDIT_VIEW.

                    Entity snapshots (`before`/`after`) are deliberately not returned: they
                    may contain personal data, and doc 09 §6 makes access to that separately
                    permission-controlled.
                    """)
    public ApiResponse<List<AdminDtos.AuditEntry>> audit(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queries.searchAudit(ActorContext.requireUserId(),
                actorId, entityType, entityId, action, limit));
    }

    // ── Configuration ────────────────────────────────────────────────────

    @GetMapping("/config")
    @Operation(summary = "Operational configuration",
            description = "Requires CONFIG_VIEW, which does not imply CONFIG_MANAGE. "
                    + "Pass includeHistory=true for superseded versions.")
    public ApiResponse<List<AdminDtos.ConfigEntry>> config(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false, defaultValue = "false") boolean includeHistory) {
        return ApiResponse.ok(config.list(
                ActorContext.requireUserId(), prefix, includeHistory));
    }

    @PatchMapping("/config")
    @Operation(
            summary = "Change a configuration value",
            description = """
                    Requires CONFIG_MANAGE and a reason. The change is versioned rather than
                    written over the old one: doc 09 §11 needs settlement to be reproducible,
                    which is impossible if the rate that applied in March can be edited in
                    June. Pass `effectiveFrom` to schedule it.
                    """)
    public ApiResponse<AdminDtos.ConfigEntry> updateConfig(
            @Valid @RequestBody AdminDtos.UpdateConfigRequest request) {
        return ApiResponse.ok(config.update(ActorContext.requireUserId(), request));
    }

    // ── Dashboard ────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    @Operation(
            summary = "Operational health",
            description = """
                    Doc 08 §11's figures: active orders, acceptance SLA, fill rate, delivery
                    failure, credit overdue, payment reconciliation and dispute volume.

                    Every rate is null where nothing was measured. A fresh environment
                    showing 100% acceptance because no orders were placed is worse than one
                    showing a dash.
                    """)
    public ApiResponse<AdminDtos.OperationsDashboard> dashboard(
            @RequestParam(required = false) Integer windowDays) {
        return ApiResponse.ok(dashboard.dashboard(ActorContext.requireUserId(), windowDays));
    }
}
