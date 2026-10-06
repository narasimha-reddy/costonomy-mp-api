package com.costonomy.mp.credit.web.dto;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    /** One thing that happened on this store's line with the outlet. */
    public record HistoryEvent(
            Instant at,
            /** REQUESTED, APPROVED, MODIFIED, REJECTED, SUSPENDED, REINSTATED, CLOSED or EXPIRED. */
            String event,
            /** The reason or note given at the time; may be null. */
            String note) {
    }

    /**
     * What this store knows about the outlet asking for credit (D-139). Every figure is this store's own: nothing about
     * the restaurant's dealings with any other supplier is ever included.
     */
    public record RequestContextResponse(
            Long agreementId,
            CreditAgreementStatus status,
            Long outletId,
            String outletName,
            String restaurantName,
            /** The India day the figures were worked out for. */
            LocalDate asOf,
            int windowDays,
            /** Orders this store took from the outlet in the window (not drafts, not cancelled). */
            int ordersCount90d,
            BigDecimal ordersValue90d,
            /** Value over count, to 2 places; null when there are no orders. */
            BigDecimal averageOrderValue,
            int cancelledOrders90d,
            /** India days of this store's first and last orders from the outlet, ever; null when none. */
            LocalDate firstOrderDate,
            LocalDate lastOrderDate,
            /** This store's invoices to the outlet that were ever marked overdue, paid since or not. */
            int previousOverdueCount,
            /** How an earlier line with this store ended: REJECTED, CLOSED or EXPIRED; null when none did. */
            String pastLineStatus,
            Instant pastLineEndedAt,
            /** Newest first. */
            List<HistoryEvent> history) {
    }
}
