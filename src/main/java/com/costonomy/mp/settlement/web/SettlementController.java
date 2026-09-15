package com.costonomy.mp.settlement.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.settlement.domain.SettlementStatus;
import com.costonomy.mp.settlement.service.SettlementReconciliationService;
import com.costonomy.mp.settlement.service.SettlementService;
import com.costonomy.mp.settlement.web.dto.SettlementDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Settlements. Doc 04 §19, doc 05 §33.
 *
 * <p>Two audiences on one resource: a supplier reads their own statements, and
 * operations reads and moves all of them. The split is by permission rather than
 * by path, because it is genuinely the same object — and a supplier seeing exactly
 * what operations sees is the point of a statement.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Settlement")
public class SettlementController {

    private final SettlementService settlements;
    private final SettlementReconciliationService reconciliation;

    // ── Supplier ─────────────────────────────────────────────────────────

    @GetMapping("/supplier-stores/{storeId}/settlements")
    @Operation(
            summary = "A store's settlement statements",
            description = """
                    Newest first. Each carries every line of
                    `Gross - Commission ± Adjustments = Net`, and each order line carries the
                    commission rate that applied *when it was calculated* — a statement read
                    next year does not move because a rate was renegotiated this morning.
                    """)
    public ApiResponse<List<SettlementDtos.SettlementResponse>> forStore(
            @PathVariable Long storeId) {
        return ApiResponse.ok(settlements.forStore(ActorContext.requireUserId(), storeId));
    }

    @GetMapping("/settlements/{id}")
    @Operation(summary = "One settlement",
            description = "Readable by the supplier being paid and by operations.")
    public ApiResponse<SettlementDtos.SettlementResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(settlements.get(ActorContext.requireUserId(), id));
    }

    // ── Operations ───────────────────────────────────────────────────────

    @GetMapping("/admin/settlements")
    @Operation(summary = "Search settlements", description = "Requires SETTLEMENT_OPERATE.")
    public ApiResponse<List<SettlementDtos.SettlementResponse>> search(
            @RequestParam(required = false) SettlementStatus status) {
        return ApiResponse.ok(settlements.search(ActorContext.requireUserId(), status));
    }

    @PostMapping("/admin/settlements/{id}/approve")
    @Operation(
            summary = "Approve a settlement for payment",
            description = """
                    The human step doc 03 §13 puts between a calculated figure and money
                    leaving the platform. Nothing approves automatically, and the figures
                    freeze here — a later correction belongs to the next settlement, with
                    its own reason.
                    """)
    public ApiResponse<SettlementDtos.SettlementResponse> approve(
            @PathVariable Long id,
            @RequestBody(required = false) SettlementDtos.ApproveSettlementRequest request) {
        return ApiResponse.ok(settlements.approve(ActorContext.requireUserId(), id,
                request == null ? null : request.note()));
    }

    @PostMapping("/admin/settlements/{id}/processing")
    @Operation(summary = "Hand a settlement to payment")
    public ApiResponse<SettlementDtos.SettlementResponse> processing(@PathVariable Long id) {
        return ApiResponse.ok(settlements.markProcessing(ActorContext.requireUserId(), id));
    }

    @PostMapping("/admin/settlements/{id}/paid")
    @Operation(summary = "Record that a settlement was paid",
            description = "Idempotent: confirming a payment twice does not pay it twice.")
    public ApiResponse<SettlementDtos.SettlementResponse> paid(
            @PathVariable Long id,
            @Valid @RequestBody SettlementDtos.MarkPaidRequest request) {
        return ApiResponse.ok(settlements.markPaid(
                ActorContext.requireUserId(), id, request.reference()));
    }

    @PostMapping("/admin/settlements/{id}/failed")
    @Operation(summary = "Record that a payout failed",
            description = "Returns to PROCESSING on retry, keeping the approval it has.")
    public ApiResponse<SettlementDtos.SettlementResponse> failed(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        return ApiResponse.ok(settlements.markFailed(ActorContext.requireUserId(), id,
                request.getOrDefault("reason", "Payout failed")));
    }

    @PostMapping("/admin/settlements/{id}/adjustments")
    @Operation(
            summary = "Add a correction",
            description = """
                    A CREDIT adds to the payout, a DEBIT subtracts, and both need a reason —
                    an unexplained deduction from a supplier's payout is what doc 01 §17's
                    audit requirement exists to prevent.

                    Only while the settlement is still mutable. Once approved the figure is
                    a commitment and a correction goes to the next one.
                    """)
    public ApiResponse<SettlementDtos.SettlementResponse> adjust(
            @PathVariable Long id,
            @Valid @RequestBody SettlementDtos.AddAdjustmentRequest request) {
        return ApiResponse.ok(settlements.adjust(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/admin/settlements/{id}/reconcile")
    @Operation(
            summary = "Check a settlement against captured payments",
            description = """
                    Two independent records of the same money: what the order records say a
                    supplier is owed, and what the payment records say restaurants paid. A
                    mismatch is recorded rather than thrown — it needs a human, not a retry —
                    and the payout waits where it was.

                    Idempotent: it recomputes from the same two sources and overwrites its
                    own last answer.
                    """)
    public ApiResponse<SettlementDtos.ReconciliationResponse> reconcile(@PathVariable Long id) {
        return ApiResponse.ok(reconciliation.reconcile(ActorContext.requireUserId(), id));
    }
}
