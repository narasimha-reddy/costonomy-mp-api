package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.credit.service.CreditAgreementService;
import com.costonomy.mp.credit.service.CreditRepaymentService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Credit. Doc 04 §13.
 *
 * <p>Both sides of the same arrangement live here: the restaurant asks and reads,
 * the supplier answers and sets terms. Which of the two a caller is comes from
 * their grants, never from a parameter.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditController {

    private final CreditAgreementService agreements;
    private final CreditRepaymentService repayments;

    // ── Restaurant ───────────────────────────────────────────────────────

    @PostMapping("/credit/requests")
    @Operation(summary = "Ask a supplier for credit",
            description = """
                    Creates the agreement in REQUESTED and records the ask. The supplier
                    decides the terms; nothing is available to spend until they approve
                    and — where they changed the terms — the restaurant accepts.
                    """)
    public ApiResponse<CreditDtos.AgreementResponse> request(
            @Valid @RequestBody CreditDtos.CreateRequest body) {
        return ApiResponse.ok(agreements.request(ActorContext.requireUserId(), body));
    }

    @PostMapping("/credit/agreements/{id}/accept")
    @Operation(summary = "Accept terms the supplier changed",
            description = "APPROVED → ACTIVE. Credit on terms nobody agreed to is not credit.")
    public ApiResponse<CreditDtos.AgreementResponse> accept(@PathVariable Long id) {
        return ApiResponse.ok(agreements.accept(ActorContext.requireUserId(), id));
    }

    @GetMapping("/outlets/{outletId}/credit/summary")
    @Operation(summary = "An outlet's whole credit position",
            description = """
                    Approved, reserved, utilized, available, due and overdue — across every
                    supplier, and per agreement. `available` is always computed here;
                    the app must never derive it (§23A.24).
                    """)
    public ApiResponse<CreditDtos.SummaryResponse> summary(@PathVariable Long outletId) {
        return ApiResponse.ok(agreements.summary(ActorContext.requireUserId(), outletId));
    }

    @GetMapping("/outlets/{outletId}/credit/agreements")
    @Operation(summary = "An outlet's credit agreements")
    public ApiResponse<List<CreditDtos.AgreementResponse>> forOutlet(@PathVariable Long outletId) {
        return ApiResponse.ok(agreements.forOutlet(ActorContext.requireUserId(), outletId));
    }

    // ── Supplier ─────────────────────────────────────────────────────────

    @GetMapping("/supplier-stores/{storeId}/credit/agreements")
    @Operation(summary = "Credit a store has extended, and requests waiting on it")
    public ApiResponse<List<CreditDtos.AgreementResponse>> forStore(@PathVariable Long storeId) {
        return ApiResponse.ok(agreements.forStore(ActorContext.requireUserId(), storeId));
    }

    @PostMapping("/credit/agreements/{id}/approve")
    @Operation(summary = "Approve a credit request",
            description = """
                    Leave the limit and period out to approve exactly what was asked, which
                    activates the agreement immediately. Supplying either makes this a
                    modification: the agreement waits in APPROVED until the restaurant
                    accepts (doc 04 §13).
                    """)
    public ApiResponse<CreditDtos.AgreementResponse> approve(
            @PathVariable Long id, @Valid @RequestBody CreditDtos.ApproveRequest body) {
        return ApiResponse.ok(agreements.approve(ActorContext.requireUserId(), id, body));
    }

    @PostMapping("/credit/agreements/{id}/reject")
    @Operation(summary = "Reject a credit request")
    public ApiResponse<CreditDtos.AgreementResponse> reject(
            @PathVariable Long id, @Valid @RequestBody CreditDtos.RejectRequest body) {
        return ApiResponse.ok(agreements.reject(ActorContext.requireUserId(), id, body));
    }

    @PostMapping("/credit/agreements/{id}/modify")
    @Operation(summary = "Change the terms of a live agreement",
            description = """
                    Every change is versioned and needs a reason (doc 01 §18). Commitments
                    already made stand: a limit cut below current exposure simply leaves
                    nothing available until the outstanding orders resolve.
                    """)
    public ApiResponse<CreditDtos.AgreementResponse> modify(
            @PathVariable Long id, @Valid @RequestBody CreditDtos.ModifyRequest body) {
        return ApiResponse.ok(agreements.modify(ActorContext.requireUserId(), id, body));
    }

    @PostMapping("/credit/agreements/{id}/suspend")
    @Operation(summary = "Suspend a credit line",
            description = "Stops new orders. Existing debt and reservations are untouched.")
    public ApiResponse<CreditDtos.AgreementResponse> suspend(
            @PathVariable Long id, @Valid @RequestBody CreditDtos.SuspendRequest body) {
        return ApiResponse.ok(agreements.suspend(ActorContext.requireUserId(), id, body));
    }

    @PostMapping("/credit/agreements/{id}/reinstate")
    @Operation(summary = "Lift a suspension")
    public ApiResponse<CreditDtos.AgreementResponse> reinstate(@PathVariable Long id) {
        return ApiResponse.ok(agreements.reinstate(ActorContext.requireUserId(), id));
    }

    // ── Either side ──────────────────────────────────────────────────────

    @GetMapping("/credit/agreements/{id}")
    @Operation(summary = "One credit agreement")
    public ApiResponse<CreditDtos.AgreementResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(agreements.get(ActorContext.requireUserId(), id));
    }

    @GetMapping("/credit/agreements/{id}/ledger")
    @Operation(summary = "Every movement on this credit line",
            description = "Newest first. Each row carries the balances as they stood after it.")
    public ApiResponse<List<CreditDtos.LedgerEntryResponse>> ledger(@PathVariable Long id) {
        return ApiResponse.ok(agreements.ledger(ActorContext.requireUserId(), id));
    }

    @GetMapping("/credit/agreements/{id}/invoices")
    @Operation(summary = "Invoices raised against this credit line")
    public ApiResponse<List<CreditDtos.InvoiceResponse>> invoices(@PathVariable Long id) {
        return ApiResponse.ok(agreements.invoicesFor(ActorContext.requireUserId(), id));
    }

    @PostMapping("/credit/invoices/{id}/payments")
    @Operation(summary = "Record a repayment",
            description = """
                    Recorded, not collected: the money moves directly between restaurant and
                    supplier and this reconciles it, reducing the outlet's utilization by the
                    same amount. Idempotent on `Idempotency-Key` — a repeated call must not
                    reduce the debt twice.
                    """)
    public ApiResponse<CreditDtos.PaymentResponse> recordPayment(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreditDtos.RecordPaymentRequest body) {

        return ApiResponse.ok(repayments.record(
                ActorContext.requireUserId(), id, body, idempotencyKey));
    }
}
