package com.costonomy.mp.credit.web.dto;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

/** The line-lifecycle endpoints: closing a line, moving an invoice's due date, and what a request context shows. */
public final class CreditLifecycleDtos {

    private CreditLifecycleDtos() {
    }

    public record CloseRequest(
            @NotBlank(message = "Give a reason") @Size(max = 500) String reason) {
    }

    /** Move an invoice's due date later. The new date is an India calendar day. */
    public record ExtendDueRequest(
            @NotNull(message = "Choose the new due date") LocalDate newDueDate,
            @NotBlank(message = "Give a reason") @Size(min = 3, max = 500, message = "Give a reason of 3 to 500 characters")
            String reason) {
    }

    /** One extension of an invoice's due date, as either side reads it. */
    public record DueExtensionResponse(
            Long id,
            LocalDate oldDueDate,
            LocalDate newDueDate,
            String reason,
            /** The user who extended it. */
            Long extendedBy,
            Instant createdAt) {
    }

    /** The invoice after the extension, the extension itself, and the line's status (a suspension may have lifted). */
    public record ExtendDueResponse(
            CreditDtos.InvoiceResponse invoice,
            DueExtensionResponse extension,
            CreditAgreementStatus agreementStatus) {
    }
}
