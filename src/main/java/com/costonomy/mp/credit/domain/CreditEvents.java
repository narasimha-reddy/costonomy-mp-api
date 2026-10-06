package com.costonomy.mp.credit.domain;

import java.util.List;

/**
 * Every domain event name the credit module publishes (D-044, D-120).
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
    /** A restaurant repaid from its own wallet; the supplier is told (D-123). */
    public static final String REPAYMENT_RECEIVED = "CreditRepaymentReceived";
    /** A restaurant says it paid a supplier directly; the supplier is asked to confirm (D-125). */
    public static final String CLAIM_SUBMITTED = "CreditClaimSubmitted";
    /** The supplier confirmed a claim; the restaurant is told, instead of CreditRepaymentRecorded (D-125). */
    public static final String CLAIM_CONFIRMED = "CreditClaimConfirmed";
    /** The supplier could not confirm a claim; the restaurant is told why (D-125). */
    public static final String CLAIM_REJECTED = "CreditClaimRejected";
    /** The supplier closed a credit line; the restaurant is told (D-136). */
    public static final String CLOSED = "CreditClosed";
    /** An offer the restaurant never accepted lapsed after 14 India days; both sides are told (D-137). */
    public static final String OFFER_EXPIRED = "CreditOfferExpired";
    /** A supplier gave an invoice longer to be paid; the restaurant is told (D-138). */
    public static final String DUE_DATE_EXTENDED = "CreditDueDateExtended";
    /**
     * A supplier's or the system's reminder to a restaurant about what it owes (D-142). The channels depend on the
     * reminder, so the payload names a {@code notificationVariant}: IN_APP, PUSH or SMS (see NotificationRules).
     */
    public static final String REMINDER = "CreditReminder";
    // Bookkeeping on the exposure ledger; nobody is told.
    public static final String RESERVED = "CreditReserved";
    public static final String UTILIZED = "CreditUtilized";
    public static final String RELEASED = "CreditReleased";

    /** Every event the module publishes. Add a new constant here too. */
    public static final List<String> ALL = List.of(
            REQUESTED, APPROVED, REJECTED, MODIFIED, SUSPENDED, REINSTATED, OVERDUE,
            INVOICE_ISSUED, REPAYMENT_RECORDED, REPAYMENT_RECEIVED, CLAIM_SUBMITTED, CLAIM_CONFIRMED,
            CLAIM_REJECTED, CLOSED, OFFER_EXPIRED, DUE_DATE_EXTENDED, REMINDER, RESERVED, UTILIZED, RELEASED);

    private CreditEvents() {
    }
}
