package com.costonomy.mp.credit.domain;

/** Why a reminder was sent (D-142): a supplier asked for it, or one of the three automatic ones. */
public enum CreditReminderKind {
    MANUAL,
    /** Three India days before the due date; in-app only. */
    AUTO_T3,
    /** On the due date, from 10:00 IST; in-app and push. */
    AUTO_DUE,
    /** Every seven days while overdue, at most four times; in-app and push. */
    AUTO_WEEKLY
}
