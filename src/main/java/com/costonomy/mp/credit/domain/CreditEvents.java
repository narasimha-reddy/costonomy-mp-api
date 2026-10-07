package com.costonomy.mp.credit.domain;

import java.util.List;

/**
 * Every domain event name the credit module publishes (D-044, D-150).
 *
 * <p>An event name is a contract read by notifications, realtime and analytics, so the credit
 * module names each one here and nowhere else. {@link #ALL} lets a test prove that every name
 * either has a notification rule or is deliberately silent.
 */
public final class CreditEvents {

    public static final String REQUESTED = "CreditRequested";
    public static final String APPROVED = "CreditApproved";
    public static final String REJECTED = "CreditRejected";
    public static final String MODIFIED = "CreditModified";
    public static final String SUSPENDED = "CreditSuspended";
    public static final String REINSTATED = "CreditReinstated";
    public static final String OVERDUE = "CreditOverdue";
    public static final String INVOICE_ISSUED = "CreditInvoiceIssued";
    public static final String REPAYMENT_RECORDED = "CreditRepaymentRecorded";
    /** A restaurant repaid from its own wallet; the supplier is told (D-153). */
    public static final String REPAYMENT_RECEIVED = "CreditRepaymentReceived";
    /** A restaurant says it paid a supplier directly; the supplier is asked to confirm (D-155). */
    public static final String CLAIM_SUBMITTED = "CreditClaimSubmitted";
    /** The supplier confirmed a claim; the restaurant is told, instead of CreditRepaymentRecorded (D-155). */
    public static final String CLAIM_CONFIRMED = "CreditClaimConfirmed";
    /** The supplier could not confirm a claim; the restaurant is told why (D-155). */
    public static final String CLAIM_REJECTED = "CreditClaimRejected";
    /** The supplier closed a credit line; the restaurant is told (D-165). */
    public static final String CLOSED = "CreditClosed";
    /** An offer the restaurant never accepted lapsed after 14 India days; both sides are told (D-166). */
    public static final String OFFER_EXPIRED = "CreditOfferExpired";
    /** A supplier gave an invoice longer to be paid; the restaurant is told (D-167). */
    public static final String DUE_DATE_EXTENDED = "CreditDueDateExtended";
    /** The supplier undid a payment it had recorded; the restaurant is told (D-169). */
    public static final String PAYMENT_REVERSED = "CreditPaymentReversed";
    /**
     * A supplier's or the system's reminder to a restaurant about what it owes (D-171). The channels depend on the
     * reminder, so the payload names a {@code notificationVariant}: IN_APP, PUSH or SMS (see NotificationRules).
     */
    public static final String REMINDER = "CreditReminder";
    /** The supplier store's daily credit summary, in-app and push, never SMS (D-173). */
    public static final String SUPPLIER_DIGEST = "CreditSupplierDigest";
    /** A credit note was issued on an invoice, by the supplier or automatically on a cancelled order; the restaurant is told (D-175). */
    public static final String CREDIT_NOTE_ISSUED = "CreditNoteIssued";
    /** The supplier wrote off what was still owed on an invoice; the restaurant is told, in-app only (D-179). */
    public static final String WRITTEN_OFF = "CreditWrittenOff";
    /** A cancelled order left money the restaurant had paid; the supplier is told it owes a refund (D-177). */
    public static final String REFUND_DUE = "CreditRefundDue";
    // Bookkeeping on the exposure ledger; nobody is told.
    public static final String RESERVED = "CreditReserved";
    public static final String UTILIZED = "CreditUtilized";
    public static final String RELEASED = "CreditReleased";

    /** Every event the module publishes. Add a new constant here too. */
    public static final List<String> ALL = List.of(
            REQUESTED, APPROVED, REJECTED, MODIFIED, SUSPENDED, REINSTATED, OVERDUE,
            INVOICE_ISSUED, REPAYMENT_RECORDED, REPAYMENT_RECEIVED, CLAIM_SUBMITTED, CLAIM_CONFIRMED,
            CLAIM_REJECTED, PAYMENT_REVERSED, CLOSED, OFFER_EXPIRED, DUE_DATE_EXTENDED, REMINDER, SUPPLIER_DIGEST,
            CREDIT_NOTE_ISSUED, WRITTEN_OFF, REFUND_DUE, RESERVED, UTILIZED, RELEASED);

    private CreditEvents() {
    }
}
