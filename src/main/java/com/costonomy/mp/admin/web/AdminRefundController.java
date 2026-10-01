package com.costonomy.mp.admin.web;

import com.costonomy.mp.admin.web.dto.AdminRefundDtos;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.payment.domain.RefundStatus;
import com.costonomy.mp.payment.provider.ProviderFailureKind;
import com.costonomy.mp.payment.service.RefundOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refunds the payment provider did not send, and what a person can do about them (D-110).
 *
 * <p>Reading needs {@code PAYMENT_INSPECT}; every action needs {@code REFUND_OPERATE}, a note, and
 * is audited. Every action that moves money is checked against the provider's own list of the
 * payment's refunds before it runs, and is refused if that does not support it.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin: refunds")
public class AdminRefundController {

    private final RefundOperations operations;

    @GetMapping("/refunds")
    @Operation(summary = "Refunds waiting for a person",
            description = "Refunds in NEEDS_REVIEW or REJECTED by default (`status` takes a comma-separated list), "
                    + "newest first, optionally by `kind` (the failure kind, for example AMBIGUOUS). "
                    + "Requires PAYMENT_INSPECT.")
    public ApiResponse<List<AdminRefundDtos.RefundSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer limit) {
        Set<RefundStatus> statuses;
        ProviderFailureKind failureKind;
        try {
            statuses = status == null || status.isBlank() ? null
                    : Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> RefundStatus.valueOf(s.toUpperCase(java.util.Locale.ROOT)))
                    .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(RefundStatus.class)));
            failureKind = kind == null || kind.isBlank() ? null
                    : ProviderFailureKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new com.costonomy.mp.common.error.BusinessException(
                    com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                    "Unknown status or kind. Statuses: " + Arrays.toString(RefundStatus.values())
                            + ". Kinds: " + Arrays.toString(ProviderFailureKind.values()) + ".");
        }
        return ApiResponse.ok(operations.list(ActorContext.requireUserId(), statuses, failureKind, limit)
                .stream().map(AdminRefundDtos.RefundSummary::of).toList());
    }

    @GetMapping("/refunds/{id}")
    @Operation(summary = "One refund, with the provider's own list of the payment's refunds, read now",
            description = "Requires PAYMENT_INSPECT. If the provider cannot be read, `providerError` says so "
                    + "and the rest is still returned.")
    public ApiResponse<AdminRefundDtos.RefundDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(AdminRefundDtos.RefundDetail.of(operations.detail(ActorContext.requireUserId(), id)));
    }

    @PostMapping("/refunds/{id}/verify")
    @Operation(summary = "Read the provider's refunds for this payment now",
            description = "Requires REFUND_OPERATE and a `note`. If a refund of ours is there it is adopted "
                    + "(completed, or followed while pending); otherwise the check is recorded. A recorded "
                    + "check with none of ours, less than ten minutes old, is what a re-credit or a send to "
                    + "the wallet needs.")
    public ApiResponse<AdminRefundDtos.ActionResult> verify(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.NoteRequest request) {
        var result = operations.verify(ActorContext.requireUserId(), id, request.note());
        return ApiResponse.ok(new AdminRefundDtos.ActionResult(result.result(), id, result.status(), true, false));
    }

    @PostMapping("/refunds/{id}/retry")
    @Operation(summary = "Send a refund in review again, with the same key",
            description = "Requires REFUND_OPERATE and a `note`. The provider is read first: if the refund "
                    + "is already there it is adopted instead of sent. Otherwise the refund goes back to "
                    + "REQUESTED with its attempts reset and the refund job sends it.")
    public ApiResponse<AdminRefundDtos.ActionResult> retry(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.NoteRequest request) {
        var result = operations.retry(ActorContext.requireUserId(), id, request.note());
        return ApiResponse.ok(new AdminRefundDtos.ActionResult(result.result(), id, result.status(), true, false));
    }

    @PostMapping("/refunds/{id}/recredit")
    @Operation(summary = "Put a withdrawal part back in the wallet",
            description = """
                    Requires REFUND_OPERATE. **Moves money**: credits the outlet's wallet with the part
                    that was debited and not sent. Only a withdrawal part in NEEDS_REVIEW. Needs a
                    `verify` from the last ten minutes that showed no refund of ours
                    (409 REFUND_VERIFICATION_REQUIRED otherwise), `confirmNoProviderRefund: true`, a
                    `note`, and, when the refund's outcome was never known (AMBIGUOUS), `evidence`.
                    Above the second-approver threshold (₹10,000 by default) the first call only records
                    the request (`awaitingSecondApprover: true`) and a different person must make the
                    same call. One credit per refund, whoever calls and however often.

                    **A payment the provider does not know** (old or other keys, deleted): `verify` answers
                    `UNKNOWN_PAYMENT` only when the provider's list shows nothing, its payment read says unknown,
                    and its keys were proven to work just now (a successful read of a later payment of ours;
                    503 PROVIDER_UNAVAILABLE if that could not be proven, e.g. wrong keys or mode). Such a part
                    is then put back only by **two different people, whatever it is worth**, each sending
                    `evidence` (which Razorpay account or keys the payment belongs to, at least 15 characters)
                    and `confirmPaymentOnOtherAccount: true`, at least the minimum time after the last send, on a
                    verification under ten minutes old, and after the provider is read again inside the call
                    (409 REFUND_VERIFICATION_REQUIRED if it then knows or no longer knows the payment). A payment
                    the provider knows that shows a refund that is not ours is never put back this way.
                    """)
    public ApiResponse<AdminRefundDtos.ActionResult> recredit(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.RecreditRequest request) {
        var d = operations.recredit(ActorContext.requireUserId(), id, request.note(),
                request.confirmNoProviderRefund(), request.evidence(), request.confirmPaymentOnOtherAccount());
        return ApiResponse.ok(new AdminRefundDtos.ActionResult(d.done() ? "DONE" : "AWAITING_SECOND_APPROVER",
                id, d.status(), d.done(), d.awaitingSecondApprover()));
    }

    @PostMapping("/refunds/{id}/to-wallet")
    @Operation(summary = "Send a cancellation refund the provider would not make to the wallet instead",
            description = """
                    Requires REFUND_OPERATE. **Moves money**: the refund is closed as REVERSED and the
                    restaurant's wallet is credited with a refund of the same payment and amount (key
                    `cancel-wallet-{id}`). Only a cancellation refund to the original payment in
                    NEEDS_REVIEW. The same gates as a re-credit: a fresh `verify` showing none of ours,
                    `confirmNoProviderRefund`, a `note`, and a second person above the threshold. For a payment
                    the provider does not know (`verify` answered `UNKNOWN_PAYMENT`) the rules of `recredit`
                    for that case apply: two people always, `evidence` and `confirmPaymentOnOtherAccount`.
                    """)
    public ApiResponse<AdminRefundDtos.ActionResult> toWallet(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.ToWalletRequest request) {
        var d = operations.toWallet(ActorContext.requireUserId(), id, request.note(), request.confirmNoProviderRefund(),
                request.evidence(), request.confirmPaymentOnOtherAccount());
        return ApiResponse.ok(new AdminRefundDtos.ActionResult(d.done() ? "DONE" : "AWAITING_SECOND_APPROVER",
                id, d.status(), d.done(), d.awaitingSecondApprover()));
    }

    @PostMapping("/refunds/{id}/mark-completed")
    @Operation(summary = "The money did reach the payer: complete a refund against the provider's refund",
            description = """
                    Requires REFUND_OPERATE. The named refund must be listed by the provider under this
                    refund's payment, be processed, match the amount, and not complete another refund of ours
                    (422 REFUND_VERIFICATION_FAILED otherwise).

                    A withdrawal part can also be closed against a refund made outside Mandi that is for more
                    than this part (one dashboard refund of the whole payment, or one refund for two parts):
                    send `confirmPayerRefundedInFull: true`. The provider must show the payment refunded in
                    full, the refund must not be ours, and the parts closed against it never add up to more
                    than it. Above the second-approver threshold (₹10,000 by default) the first call only
                    records the request (`awaitingSecondApprover: true`) and a different person must make the
                    same call.

                    A withdrawal part and a refund made outside Mandi that **covers it although the amounts differ and
                    the payment is not refunded in full** (payment 1000, part 400 refused, 500 refunded by hand: the 400
                    and 100 goodwill): send `confirmRefundCoversThisPart: true` and `evidence` (at least 15 characters:
                    who made the refund and what shows it is for this part). The refund must be listed, processed and not
                    ours; **always two different people**, after a fresh `verify` (under ten minutes old) that showed a
                    refund that is not ours; the parts closed against one refund never add up to more than it. A part whose
                    send was never answered (AMBIGUOUS) is not closed within 30 minutes of its last send (our own send may still land). The part is
                    COMPLETED (the payer has the money), the wallet is NOT credited, the source payment is blocked
                    `REFUNDED_ELSEWHERE`. Do not `retry` such a part: the provider would send it a second time.

                    A withdrawal part that carries a provider refund id, on a payment the provider does not know
                    (its last `verify`, from the last ten minutes, answered `UNKNOWN_PAYMENT`): send the part's own
                    `providerRefundId`, `evidence` (at least 15 characters: the other Razorpay account and what shows the
                    refund was processed there) and `confirmProcessedOnOtherAccount: true`. **Always two different
                    people.** The provider is read again inside the call (payment still unknown, keys proven to work). The
                    part is COMPLETED (the payer has the money), the wallet is NOT credited, and the source payment is
                    blocked `PAYMENT_UNKNOWN`. If the refund was not processed there, this is not the way: it needs
                    engineering.
                    """)
    public ApiResponse<AdminRefundDtos.ActionResult> markCompleted(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.MarkCompletedRequest request) {
        var result = operations.markCompleted(ActorContext.requireUserId(), id, request.providerRefundId(), request.note(),
                request.confirmPayerRefundedInFull(), request.evidence(), request.confirmProcessedOnOtherAccount(),
                request.confirmRefundCoversThisPart());
        boolean awaiting = "AWAITING_SECOND_APPROVER".equals(result.result());
        return ApiResponse.ok(new AdminRefundDtos.ActionResult(result.result(), id, result.status(), !awaiting, awaiting));
    }

    @PostMapping("/refunds/{id}/foreign-refund-not-this-part")
    @Operation(summary = "A refund made outside Mandi is not this withdrawal part's",
            description = """
                    Requires REFUND_OPERATE. Records a judgement and moves no money: the named provider refunds
                    (up to 6) are left out of the proof that the payer was not refunded another way, by id and
                    with their amounts, so that a part held back for an unrelated refund (goodwill, another
                    order) can be put back with `recredit`, which still needs its own fresh `verify` and all
                    its other gates on what remains. Only a withdrawal part in NEEDS_REVIEW whose last
                    `verify`, from the last ten minutes, found a refund that is not ours (409
                    REFUND_VERIFICATION_REQUIRED otherwise). Needs a `note`, `evidence` and the
                    `providerRefundIds`; each must be listed at the provider now, not failed and not ours, and **each worth strictly less than the part and
                    all of them (with those already recorded) together strictly less than it**: a refund of at least the part's
                    amount looks like the payer's money for exactly this part and is closed with `mark-completed` and
                    `confirmPayerRefundedInFull` instead (checked at the first approval too: 422 REFUND_VERIFICATION_FAILED,
                    nothing recorded, otherwise).
                    **Always two people**: the first call records the request
                    (`awaitingSecondApprover: true`) and a different person makes the same call, naming the
                    same refunds. Retrying the part or reading it again voids the request.
                    """)
    public ApiResponse<AdminRefundDtos.ActionResult> foreignRefundNotThisPart(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.ForeignRefundNotThisPartRequest request) {
        var d = operations.foreignRefundNotThisPart(ActorContext.requireUserId(), id, request.providerRefundIds(),
                request.note(), request.evidence());
        return ApiResponse.ok(new AdminRefundDtos.ActionResult(d.done() ? "DONE" : "AWAITING_SECOND_APPROVER",
                id, d.status(), d.done(), d.awaitingSecondApprover()));
    }

    @PostMapping("/refunds/{id}/resolve-late-success")
    @Operation(summary = "A refund that was put back in a wallet and was then sent by the provider has been dealt with",
            description = "Requires REFUND_OPERATE and a `note`. Only a refund the late success watch found at the provider "
                    + "after it was put back in the wallet (the restaurant was credited twice). Ends the pause on the "
                    + "outlet's withdrawals. Moves no money: adjust the wallet first, with an audited adjustment, if it should be.")
    public ApiResponse<AdminRefundDtos.ActionResult> resolveLateSuccess(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.NoteRequest request) {
        var result = operations.resolveLateSuccess(ActorContext.requireUserId(), id, request.note());
        return ApiResponse.ok(new AdminRefundDtos.ActionResult(result.result(), id, result.status(), true, false));
    }

    @PostMapping("/payments/{id}/returned-outside")
    @Operation(summary = "A cancelled order's payment was refunded by hand in the provider's dashboard",
            description = "Requires REFUND_OPERATE. Only a CANCEL_PENDING payment waiting for a person. "
                    + "Checked against the provider: refunded in full, and the named refund listed under it, "
                    + "processed, for the whole amount (422 REFUND_VERIFICATION_FAILED otherwise). Then the "
                    + "payment is recorded as refunded and the cancellation refund as completed.")
    public ApiResponse<Map<String, Object>> returnedOutside(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.ReturnedOutsideRequest request) {
        var result = operations.returnedOutside(ActorContext.requireUserId(), id, request.providerRefundId(), request.note());
        return ApiResponse.ok(Map.of("paymentStatus", result.status()));
    }

    @PostMapping("/payments/{id}/refund-block")
    @Operation(summary = "Stop drawing withdrawals from a payment",
            description = "Requires REFUND_OPERATE and a `reason`. Its wallet money stays spendable.")
    public ApiResponse<Map<String, Object>> block(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.ReasonRequest request) {
        return ApiResponse.ok(Map.of("changed", operations.blockSource(ActorContext.requireUserId(), id, request.reason())));
    }

    @PostMapping("/payments/{id}/refund-unblock")
    @Operation(summary = "Allow withdrawals to be drawn from a payment again",
            description = "Requires REFUND_OPERATE and a `reason`. Corrects a block that was wrong.")
    public ApiResponse<Map<String, Object>> unblock(
            @PathVariable Long id, @Valid @RequestBody AdminRefundDtos.ReasonRequest request) {
        return ApiResponse.ok(Map.of("changed", operations.unblockSource(ActorContext.requireUserId(), id, request.reason())));
    }
}
