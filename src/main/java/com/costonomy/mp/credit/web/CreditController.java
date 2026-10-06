package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.domain.CreditClaimStatus;
import com.costonomy.mp.credit.service.CreditAgreementService;
import com.costonomy.mp.credit.service.CreditClaimService;
import com.costonomy.mp.credit.service.CreditReadService;
import com.costonomy.mp.credit.service.CreditSupplierPaymentService;
import com.costonomy.mp.credit.service.CreditRepaymentService;
import com.costonomy.mp.credit.service.CreditWalletRepaymentService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    private final CreditWalletRepaymentService walletRepayments;
    private final CreditSupplierPaymentService supplierPayments;
    private final CreditReadService reads;
    private final CreditClaimService claims;

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
    public ApiResponse<CreditDtos.AgreementResponse> accept(
            @PathVariable Long id, @RequestBody(required = false) CreditDtos.AcceptRequest body) {
        return ApiResponse.ok(agreements.accept(ActorContext.requireUserId(), id,
                body == null ? null : body.termsVersion()));
    }

    @PostMapping("/credit/agreements/{id}/wallet-repayments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Repay credit from the wallet",
            description = """
                    Takes the amount from the outlet's wallet and settles invoices of this agreement with it:
                    the ones in `invoiceIds`, or the open ones oldest due date first. Needs `CREDIT_REPAY` on
                    the outlet and an `Idempotency-Key`. Allowed whatever the status of the line.

                    Refused with `CREDIT_OVERPAYMENT` (details: `outstanding`) when the amount is more than is
                    owed, and with `WALLET_INSUFFICIENT_BALANCE` (details: `shortBy`) when the wallet cannot
                    cover it; in both cases nothing moves. Off (403) until the supplier payout exists.
                    """)
    public ApiResponse<CreditDtos.WalletRepaymentResponse> repayFromWallet(
            @PathVariable Long id,
            @Valid @RequestBody CreditDtos.WalletRepaymentRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        // Checked by hand: a @Size on a header parameter surfaces as a 500 on this stack, not the validation error.
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The Idempotency-Key must be 1 to 100 characters.");
        }
        return ApiResponse.ok(walletRepayments.repay(
                ActorContext.requireUserId(), id, body.amount(), body.invoiceIds(), idempotencyKey));
    }

    @PostMapping("/credit/invoices/{id}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Say you paid a supplier directly",
            description = """
                    "I paid": the restaurant reports a payment made straight to the supplier (bank transfer, UPI,
                    cash, cheque or card). It is only a statement and changes nothing (not the invoice, not the
                    credit line, not an overdue mark) until the supplier confirms it. `reference` is required
                    unless `method` is CASH; `paidOn` is an India calendar day, not in the future and not before the
                    invoice was issued. Needs `CREDIT_REPAY` on the outlet and an `Idempotency-Key`.

                    Refused with `CREDIT_OVERPAYMENT` (details: `outstanding`, what can still be claimed: what is
                    owed less the invoice's other open claims) and nothing is created.
                    """)
    public ApiResponse<CreditDtos.ClaimResponse> claim(
            @PathVariable Long id,
            @Valid @RequestBody CreditDtos.ClaimRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireKey(idempotencyKey);
        return ApiResponse.ok(claims.submit(ActorContext.requireUserId(), id, body, idempotencyKey));
    }

    @PostMapping("/credit/claims/{id}/withdraw")
    @Operation(summary = "Take back a claim the supplier has not answered",
            description = "Only while SUBMITTED; otherwise 409 `CREDIT_CLAIM_STATE`. Needs `CREDIT_REPAY` on the outlet.")
    public ApiResponse<CreditDtos.ClaimResponse> withdrawClaim(@PathVariable Long id) {
        return ApiResponse.ok(claims.withdraw(ActorContext.requireUserId(), id));
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

    @GetMapping("/outlets/{outletId}/credit/attention")
    @Operation(summary = "Whether the Home Credit tile needs attention",
            description = """
                    `{overdue, dueSoon}` and nothing else: Home shows a dot, never a balance. `overdue` is any
                    invoice of this outlet marked overdue; `dueSoon` is any open invoice due within the next
                    three days (India time, grace-period invoices included) that is not already overdue.
                    Needs `CREDIT_VIEW` on the outlet; another outlet's id is a 404.
                    """)
    public ApiResponse<CreditDtos.AttentionResponse> attention(@PathVariable Long outletId) {
        return ApiResponse.ok(reads.attention(ActorContext.requireUserId(), outletId));
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

    @PostMapping("/credit/claims/{id}/confirm")
    @Operation(summary = "Confirm a restaurant's \"I paid\" claim",
            description = """
                    The money reached the supplier, so it is recorded as a payment on the invoice (source
                    CLAIM_CONFIRMED). Leave `amount` out to confirm what was claimed, capped at what is outstanding
                    now; an explicit amount must be more than zero and at most both. Anything more is
                    `CREDIT_OVERPAYMENT`. Only a SUBMITTED claim can be confirmed, else 409 `CREDIT_CLAIM_STATE`
                    (also when the invoice is already settled). Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the store
                    and an `Idempotency-Key`; confirming twice records one payment.
                    """)
    public ApiResponse<CreditDtos.ClaimResponse> confirmClaim(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) CreditDtos.ConfirmClaimRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireKey(idempotencyKey);
        return ApiResponse.ok(claims.confirm(ActorContext.requireUserId(), id,
                body == null ? null : body.amount(), idempotencyKey));
    }

    @PostMapping("/credit/claims/{id}/reject")
    @Operation(summary = "Say you could not confirm a restaurant's claim",
            description = "Needs a reason of 3 to 500 characters. No money effect. Only while SUBMITTED, else 409 "
                    + "`CREDIT_CLAIM_STATE`. Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the store.")
    public ApiResponse<CreditDtos.ClaimResponse> rejectClaim(
            @PathVariable Long id, @Valid @RequestBody CreditDtos.RejectClaimRequest body) {
        return ApiResponse.ok(claims.reject(ActorContext.requireUserId(), id, body.reason()));
    }

    @GetMapping("/supplier-stores/{storeId}/credit/claims")
    @Operation(summary = "The store's inbox of \"I paid\" claims",
            description = "Newest first, with the restaurant, outlet and invoice number; `?status=SUBMITTED` for what "
                    + "is waiting on the supplier. Needs `CREDIT_VIEW` or `CREDIT_REQUEST_VIEW` on the store; "
                    + "anyone else gets a 404.")
    public ApiResponse<List<CreditDtos.ClaimResponse>> storeClaims(
            @PathVariable Long storeId, @RequestParam(required = false) CreditClaimStatus status) {
        return ApiResponse.ok(claims.forStore(ActorContext.requireUserId(), storeId, status));
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

    @GetMapping("/credit/agreements/{id}/claims")
    @Operation(summary = "The \"I paid\" claims on this credit line",
            description = "Newest first, optionally `?status=`. Same access as the agreement's ledger.")
    public ApiResponse<List<CreditDtos.ClaimResponse>> agreementClaims(
            @PathVariable Long id, @RequestParam(required = false) CreditClaimStatus status) {
        return ApiResponse.ok(claims.forAgreement(ActorContext.requireUserId(), id, status));
    }

    @GetMapping("/credit/invoices/{id}")
    @Operation(summary = "One invoice with its payments",
            description = """
                    The invoice with `dueState` and `daysToDue` worked out here in India time, the supplier order's
                    number, who it is from, and every payment against it newest first. A payment made from the
                    wallet carries `walletEntryId`, the wallet ledger entry that paid it. `claims` lists the "I paid"
                    claims on it, newest first. Either side may read it;
                    anyone else gets a 404.
                    """)
    public ApiResponse<CreditDtos.InvoiceDetailResponse> invoice(@PathVariable Long id) {
        return ApiResponse.ok(reads.invoice(ActorContext.requireUserId(), id));
    }

    @GetMapping("/credit/agreements/{id}/statement")
    @Operation(summary = "A statement of what was owed on this credit line",
            description = """
                    Orders on credit, repayments, releases and adjustments between two India calendar days, both
                    inclusive (`from`/`to` as YYYY-MM-DD, default the last 90 days), newest first. Each line has a
                    signed `amount` and `owedAfter`; `openingOwed + sum(amount) = closingOwed`. Holds and other
                    movements that change nothing owed are left out. More than 366 days, or `to` before `from`,
                    is a validation error. Same access as the agreement's ledger.
                    """)
    public ApiResponse<CreditDtos.StatementResponse> statement(
            @PathVariable Long id,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.ok(reads.statement(ActorContext.requireUserId(), id, from, to));
    }

    @PostMapping("/credit/invoices/{id}/payments")
    @Operation(summary = "Record a repayment",
            description = """
                    Recorded, not collected: the money moves directly between restaurant and
                    supplier and this reconciles it, reducing the outlet's utilization by the
                    same amount. Idempotent on `Idempotency-Key` — a repeated call must not
                    reduce the debt twice. Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the store; anyone else gets a 404.
                    """)
    public ApiResponse<CreditDtos.PaymentResponse> recordPayment(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreditDtos.RecordPaymentRequest body) {

        return ApiResponse.ok(repayments.record(
                ActorContext.requireUserId(), id, body, idempotencyKey));
    }

    @PostMapping("/credit/agreements/{id}/payments/preview")
    @Operation(summary = "What recording a payment would do",
            description = """
                    A pure read: nothing is written, no key is needed. Returns the allocations (oldest due date first,
                    ties by invoice id; or only the chosen `invoiceIds`, in due-date order among them), each invoice's
                    `statusAfter`, the line's position after (`due`, `overdue`, `available`, `status`), and
                    `pendingClaims`: the restaurant's open "I paid" claims on the invoices that would be paid, which the
                    supplier may prefer to confirm. More than the targeted invoices owe is `CREDIT_OVERPAYMENT`
                    (details: `outstanding`). Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the store; others get 404.
                    """)
    public ApiResponse<CreditDtos.SupplierPaymentPreviewResponse> previewPayment(
            @PathVariable Long id, @Valid @RequestBody CreditDtos.SupplierPaymentPreviewRequest body) {
        return ApiResponse.ok(supplierPayments.preview(ActorContext.requireUserId(), id, body));
    }

    @PostMapping("/credit/agreements/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a payment received for a credit line",
            description = """
                    Money the restaurant paid the supplier outside Mandi. One receipt, split over the open invoices
                    oldest due date first (or the chosen `invoiceIds`), all in one transaction. `method` is CASH, UPI,
                    BANK_TRANSFER, CHEQUE or CARD (never ADJUSTMENT); `reference` (4 to 64 characters, trimmed) is
                    required for UPI, BANK_TRANSFER and CHEQUE. `paidOn` is an India calendar day, not in the future and
                    not before the oldest targeted invoice was issued. Needs an `Idempotency-Key`; a repeat returns the
                    first response. Allowed whatever the status of the line.

                    Refused with `CREDIT_OVERPAYMENT` (details: `outstanding`) when more than is owed, and with 409
                    `CREDIT_DUPLICATE_REFERENCE` (details: `receiptId`, `paidOn`, `amount`) when the same reference was
                    recorded in this store in the last 90 days, unless `allowDuplicateReference` is true. In every
                    refusal nothing moves. Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the store; others get 404.
                    """)
    public ApiResponse<CreditDtos.SupplierPaymentResponse> recordReceipt(
            @PathVariable Long id,
            @Valid @RequestBody CreditDtos.SupplierPaymentRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireKey(idempotencyKey);
        return ApiResponse.ok(supplierPayments.record(ActorContext.requireUserId(), id, body, idempotencyKey));
    }

    /** Checked by hand: a @Size on a header parameter surfaces as a 500 on this stack, not the validation error. */
    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The Idempotency-Key must be 1 to 100 characters.");
        }
    }
}
