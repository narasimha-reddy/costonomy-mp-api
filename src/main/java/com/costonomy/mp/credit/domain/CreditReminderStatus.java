package com.costonomy.mp.credit.domain;

public enum CreditReminderStatus {
    /** Asked for outside 09:00-20:00 IST; a job sends it when the window opens. */
    QUEUED,
    SENT,
    /** Queued, and by the time the window opened nothing it was about still needed a reminder. */
    CANCELLED
}
