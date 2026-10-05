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
    // Bookkeeping on the exposure ledger; nobody is told.
    public static final String RESERVED = "CreditReserved";
    public static final String UTILIZED = "CreditUtilized";
    public static final String RELEASED = "CreditReleased";

    /** Every event the module publishes. Add a new constant here too. */
    public static final List<String> ALL = List.of(
            REQUESTED, APPROVED, REJECTED, MODIFIED, SUSPENDED, REINSTATED, OVERDUE,
            INVOICE_ISSUED, REPAYMENT_RECORDED, RESERVED, UTILIZED, RELEASED);

    private CreditEvents() {
    }
}
