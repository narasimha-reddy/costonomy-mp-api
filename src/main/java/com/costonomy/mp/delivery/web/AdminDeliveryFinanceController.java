package com.costonomy.mp.delivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.delivery.service.DeliveryFinanceAdminService;
import com.costonomy.mp.delivery.service.DeliveryFinanceAdminService.PayableDetail;
import com.costonomy.mp.delivery.service.DeliveryFinanceAdminService.PayableView;
import com.costonomy.mp.delivery.service.DeliveryFinanceAdminService.Queues;
import com.costonomy.mp.delivery.service.DeliveryFinanceAdminService.RefundView;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Courier payables and gateway refunds for finance and operations (D-204). Reads need DELIVERY_FINANCE_INSPECT, writes
 * DELIVERY_FINANCE_OPERATE, at PLATFORM scope. Every write takes a note and is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/delivery-finance")
@RequiredArgsConstructor
@Tag(name = "Operations - Delivery finance")
public class AdminDeliveryFinanceController {

    private final DeliveryFinanceAdminService service;

    public record MarkPaidRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, String reference, Instant paidAt,
                                  String note) {
    }

    public record NoteRequest(String note) {
    }

    public record AmountRequest(@NotNull @DecimalMin("0.00") BigDecimal amount, String note) {
    }

    public record ResolveAmbiguousRequest(boolean paid, String reference, String note) {
    }

    public record ResolveRefundRequest(String providerRefundId, String note) {
    }

    public record BackfillRequest(@NotNull String sourceType, Long afterId, Integer limit, String note) {
    }

    public record RewindRequest(@NotNull String sourceType, String note) {
    }

    @GetMapping("/queues")
    @Operation(summary = "The finance queues: overdue, exhausted, ambiguous and unreconciled payments and refunds",
            description = "Each list is bounded at 100 and oldest first; the totals say how long each really is. "
                    + "Courier amounts (what we owe the providers) and customer refund amounts are separate. "
                    + "Requires DELIVERY_FINANCE_INSPECT at PLATFORM scope.")
    public ApiResponse<Queues> queues() {
        return ApiResponse.ok(service.queues(ActorContext.requireUserId()));
    }

    @GetMapping("/payables")
    @Operation(summary = "Search courier payables")
    public ApiResponse<DeliveryFinanceAdminService.Page<PayableView>> payables(
            @RequestParam(required = false) String state, @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String sourceType, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.listPayables(ActorContext.requireUserId(), state, providerCode, sourceType, page, size));
    }

    @GetMapping("/payables/{id}")
    @Operation(summary = "One courier payable with its payment attempts and event trail")
    public ApiResponse<PayableDetail> payable(@PathVariable Long id) {
        return ApiResponse.ok(service.payable(ActorContext.requireUserId(), id));
    }

    @PostMapping("/payables/{id}/mark-paid")
    @Operation(summary = "Record a payment made outside the system (bank transfer or the provider's invoice)")
    public ApiResponse<PayableView> markPaid(@PathVariable Long id, @Valid @RequestBody MarkPaidRequest r) {
        return ApiResponse.ok(service.markPaid(ActorContext.requireUserId(), id, r.amount(), r.reference(), r.paidAt(), r.note()));
    }

    @PostMapping("/payables/{id}/retry")
    @Operation(summary = "Put a failed or exhausted payable back in the payment queue")
    public ApiResponse<PayableView> retry(@PathVariable Long id, @RequestBody NoteRequest r) {
        return ApiResponse.ok(service.retry(ActorContext.requireUserId(), id, r.note()));
    }

    @PostMapping("/payables/{id}/void")
    @Operation(summary = "Declare that nothing is owed to the courier")
    public ApiResponse<PayableView> voidPayable(@PathVariable Long id, @RequestBody NoteRequest r) {
        return ApiResponse.ok(service.voidPayable(ActorContext.requireUserId(), id, r.note()));
    }

    @PostMapping("/payables/{id}/cancellation-fee")
    @Operation(summary = "Record the provider's cancellation fee")
    public ApiResponse<PayableView> cancellationFee(@PathVariable Long id, @Valid @RequestBody AmountRequest r) {
        return ApiResponse.ok(service.recordCancellationFee(ActorContext.requireUserId(), id, r.amount(), r.note()));
    }

    @PostMapping("/payables/{id}/provider-credit")
    @Operation(summary = "Record a credit the provider gave us")
    public ApiResponse<PayableView> providerCredit(@PathVariable Long id, @Valid @RequestBody AmountRequest r) {
        return ApiResponse.ok(service.recordProviderCredit(ActorContext.requireUserId(), id, r.amount(), r.note()));
    }

    @PostMapping("/payables/{id}/final-cost")
    @Operation(summary = "Set the provider's final charge as billed")
    public ApiResponse<PayableView> finalCost(@PathVariable Long id, @Valid @RequestBody AmountRequest r) {
        return ApiResponse.ok(service.setFinalCost(ActorContext.requireUserId(), id, r.amount(), r.note()));
    }

    @PostMapping("/payables/{id}/resolve-ambiguous")
    @Operation(summary = "Decide an ambiguous payment: it did go (give the reference) or it did not (it is retried)")
    public ApiResponse<PayableView> resolveAmbiguous(@PathVariable Long id, @RequestBody ResolveAmbiguousRequest r) {
        return ApiResponse.ok(service.resolveAmbiguous(ActorContext.requireUserId(), id, r.paid(), r.reference(), r.note()));
    }

    @PostMapping("/payables/{id}/resolve-reconciliation")
    @Operation(summary = "Close a reconciliation flag, with how it was settled")
    public ApiResponse<PayableView> resolveReconciliation(@PathVariable Long id, @RequestBody NoteRequest r) {
        return ApiResponse.ok(service.resolveReconciliation(ActorContext.requireUserId(), id, r.note()));
    }

    @GetMapping("/refunds")
    @Operation(summary = "Search gateway refunds")
    public ApiResponse<DeliveryFinanceAdminService.Page<RefundView>> refunds(
            @RequestParam(required = false) String status, @RequestParam(required = false) String sourceType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.listRefunds(ActorContext.requireUserId(), status, sourceType, page, size));
    }

    @PostMapping("/refunds/{id}/retry")
    @Operation(summary = "Put a failed or exhausted gateway refund back in the send queue")
    public ApiResponse<RefundView> retryRefund(@PathVariable Long id, @RequestBody NoteRequest r) {
        return ApiResponse.ok(service.retryRefund(ActorContext.requireUserId(), id, r.note()));
    }

    @PostMapping("/refunds/{id}/resolve")
    @Operation(summary = "Record a refund that was made in the gateway's dashboard")
    public ApiResponse<RefundView> resolveRefund(@PathVariable Long id, @RequestBody ResolveRefundRequest r) {
        return ApiResponse.ok(service.resolveRefundExternally(ActorContext.requireUserId(), id, r.providerRefundId(), r.note()));
    }

    @PostMapping("/payables/backfill")
    @Operation(summary = "Create the payables of courier bookings that have none",
            description = "One deterministic page by delivery id (after afterId, up to limit, at most 500). Repeat with the returned "
                    + "nextAfterId until remaining is 0. Safe to repeat. Requires DELIVERY_FINANCE_OPERATE.")
    public ApiResponse<com.costonomy.mp.delivery.service.CourierPayableSyncService.Backfill> backfill(
            @Valid @RequestBody BackfillRequest r) {
        return ApiResponse.ok(service.backfillPayables(ActorContext.requireUserId(), r.sourceType(),
                r.afterId() == null ? 0 : r.afterId(), r.limit() == null ? 100 : r.limit(), r.note()));
    }

    @PostMapping("/payables/sync-cursor/rewind")
    @Operation(summary = "Walk every delivery of a source through the payable sync again",
            description = "Idempotent. Use after fixing a defect that made the sync skip deliveries. Requires DELIVERY_FINANCE_OPERATE.")
    public ApiResponse<java.util.Map<String, String>> rewind(@Valid @RequestBody RewindRequest r) {
        service.rewindPayableSync(ActorContext.requireUserId(), r.sourceType(), r.note());
        return ApiResponse.ok(java.util.Map.of("sourceType", r.sourceType(), "status", "REWOUND"));
    }
}
