package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.service.CreditPayService;
import com.costonomy.mp.credit.web.dto.CreditPayDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A restaurant pays whole credit invoices online (UPI, card) or from its wallet, across suppliers, with no supplier
 * approval (D-199). Every endpoint needs {@code CREDIT_REPAY} on the outlet; anything that is not the caller's is
 * "not found". The app sends invoice ids and the outstanding amount it was shown, never an amount to pay.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit payments")
public class CreditPayController {

    private final CreditPayService payments;

    @GetMapping("/outlets/{outletId}/credit/payable-invoices")
    @Operation(summary = "The invoices a restaurant can pay now, across suppliers",
            description = """
                    D-199. Every open invoice of every credit line of the outlet, grouped by supplier and in the order to
                    show them: suppliers with something overdue first (oldest first), invoices overdue first. A supplier
                    that cannot be paid through Mandi (`payable` false) is listed read-only. `methods` says what the
                    outlet may pay with now. A pure read.

                    403 `FORBIDDEN` while `costonomy.mp.credit.online-pay.enabled` is off.
                    """)
    public ApiResponse<CreditPayDtos.PayableResponse> payable(@PathVariable Long outletId) {
        return ApiResponse.ok(payments.payable(ActorContext.requireUserId(), outletId));
    }

    @PostMapping("/outlets/{outletId}/credit/payments/quote")
    @Operation(summary = "What paying these invoices would cost, and with which methods",
            description = """
                    D-199. The server totals the invoices and says, per method, whether it is available and what it
                    would charge. A pure read, no key. Refused as create is: 404 for an invoice that is not this
                    outlet's, 409 `CREDIT_PAY_SELECTION_CHANGED` (details `changedInvoiceIds`) when an invoice is settled
                    or owes other than the `outstanding` sent, 422 `CREDIT_PAY_SUPPLIER_UNAVAILABLE`.
                    """)
    public ApiResponse<CreditPayDtos.QuoteResponse> quote(
            @PathVariable Long outletId, @Valid @RequestBody CreditPayDtos.QuoteRequest body) {
        return ApiResponse.ok(payments.quote(ActorContext.requireUserId(), outletId, body));
    }

    @PostMapping("/outlets/{outletId}/credit/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Pay whole invoices online or from the wallet",
            description = """
                    D-199. `UPI` or `CARD`: records the attempt, opens one Razorpay order for the server-computed total
                    (auto-capture) and returns it with `keyId` to open checkout; status `CREATED`. A second request for the
                    same selection reopens the same order (`reopened`) while nothing in it changed. `WALLET`: debits the
                    wallet once per supplier and settles the invoices in this request; status `PAID`.

                    Needs `CREDIT_REPAY` on the outlet and an `Idempotency-Key`. 403 `FORBIDDEN` while online payment is
                    off (or, for `WALLET`, while wallet repayment is off). 409 `CREDIT_PAY_SELECTION_CHANGED`, 422
                    `CREDIT_PAY_SUPPLIER_UNAVAILABLE`, 422 `WALLET_INSUFFICIENT_BALANCE` (details `shortBy`, `balance`),
                    403 `WALLET_ON_HOLD`, 422 `PAYMENT_FAILED` when Razorpay refuses the order (nothing was charged).
                    """)
    public ApiResponse<CreditPayDtos.AttemptResponse> create(
            @PathVariable Long outletId, @Valid @RequestBody CreditPayDtos.CreateRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireKey(idempotencyKey);
        return ApiResponse.ok(payments.create(ActorContext.requireUserId(), outletId, body, idempotencyKey));
    }

    @PostMapping("/outlets/{outletId}/credit/payments/{attemptId}/verify")
    @Operation(summary = "Confirm a payment after checkout",
            description = """
                    D-199. Send what Razorpay's checkout returned. The signature is verified, then Razorpay is asked what the
                    payment is, and the invoices are settled once if it is this attempt's, for the amount charged, and
                    captured. Safe to repeat and to race: however many calls (and webhooks, and polls) arrive, the money is
                    applied once. 409 `CREDIT_PAY_PROCESSING` when not captured yet: the money is safe and is applied when it
                    clears. Works while online payment is switched off, so money already in flight still lands.
                    """)
    public ApiResponse<CreditPayDtos.AttemptResponse> verify(
            @PathVariable Long outletId, @PathVariable Long attemptId,
            @Valid @RequestBody CreditPayDtos.VerifyRequest body) {
        return ApiResponse.ok(payments.verify(ActorContext.requireUserId(), outletId, attemptId, body));
    }

    @GetMapping("/outlets/{outletId}/credit/payments/{attemptId}")
    @Operation(summary = "Where a credit payment has got to",
            description = """
                    D-199. A read; never asks Razorpay. CREATED (waiting for the payment), PAID, EXPIRED, FAILED or REFUNDED,
                    with each invoice's outcome and any refund of invoices that changed before the payment arrived. What the
                    app polls after a pending result. Works while online payment is switched off.
                    """)
    public ApiResponse<CreditPayDtos.AttemptResponse> get(@PathVariable Long outletId, @PathVariable Long attemptId) {
        return ApiResponse.ok(payments.get(ActorContext.requireUserId(), outletId, attemptId));
    }

    /** Checked by hand: a @Size on a header parameter surfaces as a 500 on this stack, not the validation error. */
    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The Idempotency-Key must be 1 to 100 characters.");
        }
    }
}
