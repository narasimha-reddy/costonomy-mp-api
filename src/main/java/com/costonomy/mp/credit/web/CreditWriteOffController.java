package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.credit.service.CreditWriteOffService;
import com.costonomy.mp.credit.web.dto.CreditNoteDtos;
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

/** Write-off: the supplier gives up on what is owed (B8, D-179, D-180). Owner and admin only. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditWriteOffController {

    private final CreditWriteOffService writeOffs;

    @PostMapping("/credit/invoices/{id}/write-off")
    @Operation(summary = "Write off what is owed on one invoice",
            description = """
                    `amount` (optional, 2 decimals, above zero, at most what is owed; left out: everything owed), `reason`
                    (required, 3 to 500 characters), `quickReason` (optional: RESTAURANT_CLOSED, UNRECOVERABLE, SETTLED_OUTSIDE,
                    GOODWILL) and `keepLineOpen` (default false). Not a payment: nothing is collected, no payout, no commission.
                    The invoice becomes WRITTEN_OFF when nothing is left; a partial write-off keeps its status. A write-off
                    in full supersedes waiting claims. Unless `keepLineOpen`, the line is suspended by the supplier with reason
                    "Written off" (a suspension the overdue sweep imposed becomes the supplier's). The restaurant is told, in-app only.

                    Needs `CREDIT_WRITE_OFF` (owner and admin; anyone else gets a 404) and an `Idempotency-Key`; a retry replays.
                    Refused with 409 `CREDIT_WRITE_OFF_NOTHING_OWED` and 422 `CREDIT_NOTE_EXCEEDS_OUTSTANDING` (details `outstanding`).
                    A written-off invoice can no longer be paid, receipted, claimed, credited, extended or have a payment undone.
                    """)
    public ApiResponse<CreditNoteDtos.WriteOffResponse> writeOffInvoice(
            @PathVariable Long id,
            @Valid @RequestBody CreditNoteDtos.WriteOffRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        CreditNoteController.requireKey(idempotencyKey);
        return ApiResponse.ok(writeOffs.writeOffInvoice(ActorContext.requireUserId(), id, body, idempotencyKey));
    }

    @PostMapping("/credit/agreements/{id}/write-off")
    @Operation(summary = "Write off what is owed on the whole line",
            description = """
                    As the invoice write-off, over every open invoice of the line, oldest due date first (ties by invoice id).
                    `amount` left out writes off everything owed; a stated amount is spread oldest first and may leave later
                    invoices untouched. One credit note and one `CREDIT_WRITTEN_OFF` audit row per invoice, one event, one
                    suspension. Same permission, key and refusals as the invoice write-off.
                    """)
    public ApiResponse<CreditNoteDtos.WriteOffResponse> writeOffLine(
            @PathVariable Long id,
            @Valid @RequestBody CreditNoteDtos.WriteOffRequest body,
            @Parameter(description = "Client-generated key, required for this operation, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        CreditNoteController.requireKey(idempotencyKey);
        return ApiResponse.ok(writeOffs.writeOffLine(ActorContext.requireUserId(), id, body, idempotencyKey));
    }
}
