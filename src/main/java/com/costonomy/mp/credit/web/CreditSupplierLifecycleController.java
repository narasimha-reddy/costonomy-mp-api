package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.service.CreditDueExtensionService;
import com.costonomy.mp.credit.service.CreditLifecycleService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditLifecycleDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * What a supplier does to a line or an invoice beyond the negotiation: close the line, and give an invoice longer to
 * be paid. Their own controller, so the credit controller stays the negotiation (D-136, D-138).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditSupplierLifecycleController {

    private final CreditLifecycleService lifecycle;
    private final CreditDueExtensionService dueExtensions;

    @PostMapping("/credit/agreements/{id}/close")
    @Operation(summary = "Close a credit line",
            description = """
                    `ACTIVE` or `SUSPENDED` to `CLOSED`, with a reason. Refused with 409 `INVALID_STATE_TRANSITION`
                    (details: `owed`, `reserved`) while anything is owed on the line or held for an order in flight:
                    suspend it instead, and close it once it is paid. A request or an offer is declined or
                    withdrawn, not closed (409). A closed line takes no orders; the restaurant is told and may ask
                    again. Closing again answers 200 and changes nothing. Needs `CREDIT_MODIFY` on the store;
                    anyone else gets a 404.
                    """)
    public ApiResponse<CreditDtos.AgreementResponse> close(
            @PathVariable Long id, @Valid @RequestBody CreditLifecycleDtos.CloseRequest body) {
        return ApiResponse.ok(lifecycle.close(ActorContext.requireUserId(), id, body.reason()));
    }

    @PostMapping("/credit/invoices/{id}/extend-due")
    @Operation(summary = "Give an invoice longer to be paid",
            description = """
                    Moves the due date later: strictly after the current one, and at most 60 days past the invoice's
                    ORIGINAL due date however many times it is extended (400 `VALIDATION_ERROR` otherwise). The grace
                    period travels with the date. Refused with 409 `INVALID_STATE_TRANSITION` on a PAID or
                    WRITTEN_OFF invoice. An OVERDUE invoice that is no longer late (its overdue-after day is today
                    or later, India time) goes back to ISSUED, or PARTIALLY_PAID when part is paid, and a
                    suspension the overdue sweep imposed lifts when what is overdue is back within the tolerance.
                    No money moves. Every extension is kept (`extensions` on the invoice) and the restaurant is
                    told. Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the store, and an `Idempotency-Key`;
                    a retry replays. Anyone else gets a 404.
                    """)
    public ApiResponse<CreditLifecycleDtos.ExtendDueResponse> extendDue(
            @PathVariable Long id,
            @Valid @RequestBody CreditLifecycleDtos.ExtendDueRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        // Checked by hand: a @Size on a header parameter surfaces as a 500 on this stack, not the validation error.
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The Idempotency-Key must be 1 to 100 characters.");
        }
        return ApiResponse.ok(dueExtensions.extend(ActorContext.requireUserId(), id, body, idempotencyKey));
    }
}
