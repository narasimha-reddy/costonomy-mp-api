package com.costonomy.mp.payment.service;

import java.time.Instant;

/** Test access to PaymentJobs' package-private backoff rule. */
public final class PaymentJobsAccess {

    private PaymentJobsAccess() {
    }

    public static boolean cancellationOverdue(Instant since, Instant now) {
        return PaymentJobs.overdue(since, now);
    }

    public static boolean due(Instant createdAt, Instant lastAsked, Instant now) {
        return PaymentJobs.dueForIntentLookup(createdAt, lastAsked, now);
    }
}
