package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.service.CreditReminderService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditReminderDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A supplier reminding a restaurant to pay (B11, D-171). Their own controller, beside the credit controller. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditReminderController {

    private final CreditReminderService reminders;

    @PostMapping("/credit/agreements/{id}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Remind a restaurant to pay",
            description = """
                    Sends the restaurant's people a reminder about what is overdue or due within 3 days on this line.
                    `invoiceIds` (optional) limits it to those invoices, which must be open invoices of the line (400
                    otherwise); left out, every invoice that is overdue, past its due date within the grace period,
                    or due within 3 days. An invoice whose outstanding the restaurant already claims to have paid
                    (a SUBMITTED claim covers it) is left out and listed in `skipped` with reason `CLAIM_SUBMITTED`
                    (`NOT_DUE` for one asked for that is not due soon). `note` is at most 300 characters and is
                    appended to the message. The text is composed on the server: `message` is exactly what the
                    restaurant gets, and is what `/reminders/preview` returned.

                    Rules, all enforced here, India time: 1 manual reminder per line per 24 hours (429
                    `CREDIT_REMINDER_TOO_SOON`, details `nextAllowedAt`), 3 per line in a rolling 7 days and 50 per
                    store per India day (429 `CREDIT_REMINDER_LIMIT`, details `limit` WEEK or STORE_DAY, `max`,
                    `nextAllowedAt`). Nothing to remind about: 422 `CREDIT_REMINDER_NOT_NEEDED` (details `reason`
                    NOTHING_DUE or CLAIM_COVERED, `skipped`). Asked for before 09:00 or from 20:00 IST, the reminder
                    is stored `QUEUED` and sent at 09:00 by a job (`sendAt`); inside the window it is `SENT`. SMS is
                    used only when something is overdue (`channels` says). Needs `CREDIT_COLLECT` or `CREDIT_MODIFY`
                    on the store and an `Idempotency-Key` (a retry replays; the same key with a different body is
                    409 `IDEMPOTENCY_KEY_REUSE`). Anyone else gets a 404.
                    """)
    public ApiResponse<CreditReminderDtos.ReminderResponse> send(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) CreditReminderDtos.SendRequest body,
            @Parameter(description = "Client-generated key, required, 1 to 100 characters")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        // Checked by hand: a @Size on a header parameter surfaces as a 500 on this stack, not the validation error.
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The Idempotency-Key must be 1 to 100 characters.");
        }
        var request = body == null ? new CreditReminderDtos.SendRequest(null, null) : body;
        return ApiResponse.ok(reminders.send(ActorContext.requireUserId(), id, request, idempotencyKey));
    }

    @GetMapping("/credit/agreements/{id}/reminders/preview")
    @Operation(summary = "What a reminder would say, and whether it may be sent now",
            description = """
                    `canRemind`, and when false `reason`: NOTHING_DUE (nothing overdue or due within 3 days),
                    CLAIM_COVERED (every such invoice is covered by a SUBMITTED claim), TOO_SOON, WEEK_LIMIT or
                    STORE_DAY_LIMIT, with `nextAllowedAt` for the last three. `message` is the exact text; `channels`
                    where it goes; `status` SENT (now) or QUEUED (09:00 IST, `sendAt`); `invoices` each invoice
                    considered with whether it is `included` and, if not, `skipReason`. `invoiceIds` is optional,
                    comma-separated. Changes nothing. Same access as sending.
                    """)
    public ApiResponse<CreditReminderDtos.PreviewResponse> preview(
            @PathVariable Long id, @RequestParam(required = false) List<Long> invoiceIds) {
        return ApiResponse.ok(reminders.preview(ActorContext.requireUserId(), id, invoiceIds));
    }

    @GetMapping("/credit/agreements/{id}/reminders")
    @Operation(summary = "The reminders sent on a credit line",
            description = "Newest first, manual and automatic (`kind`), with `status` SENT, QUEUED or CANCELLED. "
                    + "`page` from 0, `size` default 20, at most 100. Supplier side only: `CREDIT_VIEW` on the "
                    + "store, anyone else gets a 404.")
    public ApiResponse<CreditDtos.PageOf<CreditReminderDtos.ReminderResponse>> history(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reminders.history(ActorContext.requireUserId(), id, page, size));
    }
}
