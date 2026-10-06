package com.costonomy.mp.credit.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** The line-lifecycle endpoints: closing a line, moving an invoice's due date, and what a request context shows. */
public final class CreditLifecycleDtos {

    private CreditLifecycleDtos() {
    }

    public record CloseRequest(
            @NotBlank(message = "Give a reason") @Size(max = 500) String reason) {
    }
}
