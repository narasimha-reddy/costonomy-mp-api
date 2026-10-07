package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.service.CreditNoteService;
import com.costonomy.mp.credit.service.CreditRefundDueService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditNoteDtos;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Credit notes and the refunds due after a cancelled order (B7, D-175..D-178). Its own controller, beside the credit one. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditNoteController {

    private final CreditNoteService creditNotes;
    private final CreditRefundDueService refundsDue;

    @PostMapping("/credit/invoices/{id}/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Issue a credit note on an invoice",
            description = """
                    Takes `amount` (a string of at most 2 decimals, above zero, at most what is still owed) off one
                    invoice for `reasonCode` (SHORT_SUPPLY, QUALITY, PRICE, CANCELLED, GOODWILL, OTHER), with an optional
                    `note` (500 characters) and `disputeId` (a dispute of this invoice's order). Not a payment: nothing is
                    collected, no payout is made, no commission applies. It frees the credit as a payment does, lifts a
                    suspension the overdue sweep imposed when overdue is back within tolerance, supersedes waiting claims
                    when nothing is left owed, and tells the restaurant. Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the
                    store (anyone else: 404) and an `Idempotency-Key`; a retry replays.

                    Refused with 422 `CREDIT_NOTE_EXCEEDS_OUTSTANDING` (details `outstanding`) and 409
                    `CREDIT_NOTE_INVOICE_SETTLED` (the invoice is PAID or WRITTEN_OFF: refund the restaurant directly).
                    When nothing is left owed the invoice becomes PAID (paid + credited = amount).
                    """)
    public ApiResponse<CreditNoteDtos.CreditNoteResponse> issue(
            @PathVariable Long id,
            @Valid @RequestBody CreditNoteDtos.CreditNoteRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireKey(idempotencyKey);
        return ApiResponse.ok(creditNotes.issue(ActorContext.requireUserId(), id, body, idempotencyKey));
    }

    @GetMapping("/credit/agreements/{id}/credit-notes")
    @Operation(summary = "Credit notes and write-offs on this credit line",
            description = "Newest first, each with its number, invoice, amount, reason, kind (MANUAL, SYSTEM_CANCEL, WRITE_OFF) and "
                    + "author (`createdBy` is null for the system). `page` from 0, `size` default 20, at most 100. "
                    + "Either side may read; anyone else gets a 404.")
    public ApiResponse<CreditDtos.PageOf<CreditNoteDtos.CreditNoteSummary>> list(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(creditNotes.forAgreement(ActorContext.requireUserId(), id, page, size));
    }

    @GetMapping("/supplier-stores/{storeId}/credit/refunds-due")
    @Operation(summary = "Refunds due to restaurants after cancelled orders",
            description = "Money a restaurant had already paid on an invoice whose order was cancelled, newest first, optionally "
                    + "`status=OPEN|REFUNDED`. `channel` OFF_PLATFORM is the supplier's to refund directly; WALLET was paid from the "
                    + "restaurant's Mandi wallet and is Mandi's to settle. Another store's user gets a 404.")
    public ApiResponse<List<CreditNoteDtos.RefundDueResponse>> refundsDue(
            @PathVariable Long storeId, @RequestParam(required = false) String status) {
        return ApiResponse.ok(refundsDue.forStore(ActorContext.requireUserId(), storeId, status));
    }

    @PostMapping("/credit/refunds-due/{id}/mark-refunded")
    @Operation(summary = "Mark a refund due as refunded",
            description = """
                    The supplier has refunded the restaurant directly. Optional `note` (500 characters). Needs `CREDIT_COLLECT` or
                    `CREDIT_MODIFY` on the store (anyone else: 404). Marking one that is already REFUNDED answers 200 and changes
                    nothing. A WALLET refund is refused with 409 `CREDIT_REFUND_OPS_ONLY`: Mandi settles it. No money moves here.
                    """)
    public ApiResponse<CreditNoteDtos.RefundDueResponse> markRefunded(
            @PathVariable Long id, @Valid @RequestBody(required = false) CreditNoteDtos.MarkRefundedRequest body) {
        return ApiResponse.ok(refundsDue.markRefunded(ActorContext.requireUserId(), id, body == null ? null : body.note()));
    }

    /** Checked by hand: a @Size on a header parameter surfaces as a 500 on this stack, not the validation error. */
    static void requireKey(String idempotencyKey) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The Idempotency-Key must be 1 to 100 characters.");
        }
    }
}
