package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.service.CreditReversalService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The supplier undoes a payment it recorded (B6, D-140): a reversal with a reason, never a delete. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditReversalController {

    private final CreditReversalService reversals;

    @PostMapping("/credit/receipts/{receiptId}/reverse")
    @Operation(summary = "Undo a receipt the supplier recorded",
            description = """
                    Puts the debt back on every invoice the receipt paid (all or none) and sets the receipt to
                    REVERSED. The payments stay; a reversal sits beside each, and a reversed payment is no longer
                    collected. Needs `reason` (3 to 500 characters), an `Idempotency-Key`, and `CREDIT_COLLECT` or
                    `CREDIT_MODIFY` on the store (anyone else: 404).

                    Allowed for 7 India days after the day it was recorded, 30 for a cheque. Refused with 409
                    `CREDIT_REVERSAL_WINDOW_CLOSED` (details `closedOn`, `reversibleUntil`), 409
                    `CREDIT_REVERSAL_NOT_ALLOWED` (paid through Mandi, written off), 409 `CREDIT_ALREADY_REVERSED`, and
                    422 `CREDIT_REVERSAL_NO_HEADROOM` (details `needed`, `available`, `shortBy`) when the credit the
                    payment freed has been used. A suspended or closed line still allows it when there is headroom.
                    Nothing moves on a refusal. The restaurant is told.
                    """)
    public ApiResponse<CreditDtos.ReversalResponse> reverseReceipt(
            @PathVariable Long receiptId,
            @Valid @RequestBody CreditDtos.ReversalRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireKey(idempotencyKey);
        return ApiResponse.ok(reversals.reverseReceipt(ActorContext.requireUserId(), receiptId, body.reason(), idempotencyKey));
    }

    @PostMapping("/credit/payments/{paymentId}/reverse")
    @Operation(summary = "Undo one payment recorded alone",
            description = """
                    For a payment recorded one invoice at a time or a claim the supplier confirmed (the feed item has
                    no `receiptId`). A payment that belongs to a receipt is refused with 409
                    `CREDIT_REVERSAL_NOT_ALLOWED` and details `receiptId`: undo the receipt instead. A confirmed
                    claim goes back to REJECTED. Otherwise as the receipt endpoint.
                    """)
    public ApiResponse<CreditDtos.ReversalResponse> reversePayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody CreditDtos.ReversalRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireKey(idempotencyKey);
        return ApiResponse.ok(reversals.reversePayment(ActorContext.requireUserId(), paymentId, body.reason(), idempotencyKey));
    }

    /** Checked by hand: a @Size on a header parameter surfaces as a 500 on this stack, not the validation error. */
    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The Idempotency-Key must be 1 to 100 characters.");
        }
    }
}
