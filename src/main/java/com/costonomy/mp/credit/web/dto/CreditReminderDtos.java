package com.costonomy.mp.credit.web.dto;

import com.costonomy.mp.credit.domain.CreditDueState;
import com.costonomy.mp.credit.domain.CreditReminderKind;
import com.costonomy.mp.credit.domain.CreditReminderStatus;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Reminders to a restaurant (D-142). */
public final class CreditReminderDtos {

    private CreditReminderDtos() {
    }

    /** {@code invoiceIds} left out means every invoice that is overdue or due within 3 days. */
    public record SendRequest(
            List<Long> invoiceIds,
            @Size(max = 300, message = "Keep the note to 300 characters") String note) {
    }

    /** An invoice left out of a reminder, and why: {@code CLAIM_SUBMITTED} (the restaurant already says it paid) or {@code NOT_DUE}. */
    public record Skipped(Long invoiceId, String invoiceNumber, String reason) {
    }

    public record ReminderResponse(
            Long id,
            Long agreementId,
            CreditReminderKind kind,
            /** SENT, or QUEUED when asked for outside 09:00-20:00 IST (it goes at {@code sendAt}). */
            CreditReminderStatus status,
            List<String> channels,
            /** The exact text the restaurant gets. */
            String message,
            String note,
            List<Long> invoiceIds,
            List<Skipped> skipped,
            Instant requestedAt,
            /** When a QUEUED reminder will go (09:00 IST); null once sent. */
            Instant sendAt,
            Instant sentAt,
            Long createdBy) {
    }

    public record PreviewInvoice(
            Long invoiceId,
            String invoiceNumber,
            BigDecimal outstanding,
            LocalDate dueDate,
            CreditDueState dueState,
            /** True if the reminder would be about this invoice. */
            boolean included,
            /** Why it would not be: CLAIM_SUBMITTED or NOT_DUE; null when included. */
            String skipReason) {
    }

    /**
     * What a reminder would say and whether it may be sent now. {@code reason} when {@code canRemind} is false:
     * NOTHING_DUE, CLAIM_COVERED, TOO_SOON, WEEK_LIMIT or STORE_DAY_LIMIT; {@code nextAllowedAt} for the three limits.
     */
    public record PreviewResponse(
            boolean canRemind,
            String reason,
            Instant nextAllowedAt,
            /** The exact text, null when there is nothing to say. */
            String message,
            List<String> channels,
            /** SENT if it would go now, QUEUED if it would wait for 09:00 IST; null when it cannot be sent. */
            CreditReminderStatus status,
            Instant sendAt,
            List<PreviewInvoice> invoices) {
    }
}
