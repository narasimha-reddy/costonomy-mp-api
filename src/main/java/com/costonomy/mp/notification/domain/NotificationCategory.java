package com.costonomy.mp.notification.domain;

/**
 * How §23A.27 groups the inbox.
 *
 * <p>Decided here rather than by a client pattern-matching on event names: the
 * grouping is a product decision, and two clients inferring it independently would
 * disagree the first time an event was renamed.
 */
public enum NotificationCategory {

    ORDERS,
    APPROVALS,
    PAYMENTS,
    CREDIT,
    DELIVERY,
    /** Account, catalog, ratings — everything that is not a live transaction. */
    MARKETPLACE
}
