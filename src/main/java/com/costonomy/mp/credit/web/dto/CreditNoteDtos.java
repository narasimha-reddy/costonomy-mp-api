package com.costonomy.mp.credit.web.dto;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditNoteKind;
import com.costonomy.mp.credit.domain.CreditNoteReason;
import com.costonomy.mp.credit.domain.CreditRefundDue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Credit notes, write-offs and refunds due (B7, B8). Its own file so the shared credit DTOs stay as they are. */
public final class CreditNoteDtos {

    private CreditNoteDtos() {
    }

    public record CreditNoteRequest(
            @NotNull(message = "Enter an amount")
            @DecimalMin(value = "0.01", message = "The credit note must be more than zero")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount,
            @NotBlank(message = "Choose a reason")
            @Pattern(regexp = CreditNoteReason.ALL_PATTERN,
                    message = "Choose SHORT_SUPPLY, QUALITY, PRICE, CANCELLED, GOODWILL or OTHER")
            String reasonCode,
            @Size(max = 500, message = "The note can be at most 500 characters") String note,
            /** The receiving dispute this answers, when there is one; it must be the dispute of this invoice's order. */
            Long disputeId) {
    }

    /** The invoice as a note or write-off leaves it. */
    public record InvoiceState(
            CreditInvoiceStatus status,
            BigDecimal amount,
            BigDecimal paidAmount,
            BigDecimal creditedAmount,
            BigDecimal outstanding) {
    }

    /** One credit note or write-off, as listed and as shown on an invoice. {@code createdBy} is null for the system. */
    public record CreditNoteSummary(
            Long id,
            String creditNoteNumber,
            Long invoiceId,
            String invoiceNumber,
            Long agreementId,
            BigDecimal amount,
            CreditNoteReason reasonCode,
            CreditNoteKind kind,
            String note,
            Long disputeId,
            Long createdBy,
            Instant createdAt) {
    }

    /** What issuing a credit note did: the note, the invoice as it stands and the line as it stands. */
    public record CreditNoteResponse(
            Long id,
            String creditNoteNumber,
            Long invoiceId,
            String invoiceNumber,
            Long agreementId,
            BigDecimal amount,
            CreditNoteReason reasonCode,
            CreditNoteKind kind,
            String note,
            Long disputeId,
            Long createdBy,
            Instant createdAt,
            InvoiceState invoice,
            CreditDtos.RepaymentAgreementState agreement) {
    }

    // ── Write-off (B8) ───────────────────────────────────────────────────

    public record WriteOffRequest(
            @DecimalMin(value = "0.01", message = "The amount must be more than zero")
            @Digits(integer = 15, fraction = 2, message = "Use at most two decimal places")
            BigDecimal amount,
            @NotBlank(message = "Say why you are writing this off")
            @Size(min = 3, max = 500, message = "Give a reason of 3 to 500 characters")
            String reason,
            @Pattern(regexp = "RESTAURANT_CLOSED|UNRECOVERABLE|SETTLED_OUTSIDE|GOODWILL",
                    message = "Choose RESTAURANT_CLOSED, UNRECOVERABLE, SETTLED_OUTSIDE or GOODWILL")
            String quickReason,
            /** Leave the line open. Absent or false: the line is suspended by the supplier with reason "Written off". */
            Boolean keepLineOpen) {

        public boolean keepOpen() {
            return Boolean.TRUE.equals(keepLineOpen);
        }
    }

    public record WriteOffItem(
            Long invoiceId,
            String invoiceNumber,
            Long creditNoteId,
            String creditNoteNumber,
            BigDecimal amount,
            CreditInvoiceStatus invoiceStatus,
            BigDecimal outstanding) {
    }

    public record WriteOffResponse(
            BigDecimal writtenOff,
            List<WriteOffItem> items,
            CreditAgreementStatus lineStatus,
            /** Whether this write-off left the line suspended (by it, or already). */
            boolean lineSuspended,
            CreditDtos.RepaymentAgreementState agreement) {
    }

    // ── Refunds due (B7) ─────────────────────────────────────────────────

    public record MarkRefundedRequest(@Size(max = 500, message = "The note can be at most 500 characters") String note) {
    }

    /**
     * Money a restaurant paid on an invoice whose order was cancelled. OFF_PLATFORM: the supplier refunds it directly
     * and marks it REFUNDED. WALLET: paid from the Mandi wallet, put right by Mandi (status stays OPEN here).
     */
    public record RefundDueResponse(
            Long id,
            BigDecimal amount,
            CreditRefundDue.Channel channel,
            CreditRefundDue.Status status,
            String note,
            Long invoiceId,
            String invoiceNumber,
            Long creditNoteId,
            String creditNoteNumber,
            Long agreementId,
            Long outletId,
            String outletName,
            String restaurantName,
            Instant createdAt,
            Instant refundedAt) {
    }
}
