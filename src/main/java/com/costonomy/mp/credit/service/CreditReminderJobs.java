package com.costonomy.mp.credit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** The reminder job (D-171): releases reminders that waited for the morning, then sends the automatic ones. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditReminderJobs {

    private final CreditAutoReminderService reminders;

    // A fixed-delay job with no initial delay runs once as soon as the application context starts, whatever the interval.
    // The default here is no delay, as before. The test profile sets it long, because under load that first run lands
    // late, in the middle of the first test of a class, and sends the reminder the test was about to send.
    @Scheduled(fixedDelayString = "${costonomy.mp.credit.reminder-interval:PT1H}",
            initialDelayString = "${costonomy.mp.credit.reminder-initial-delay:PT0S}")
    @SchedulerLock(name = "credit-reminders", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void runReminders() {
        int released = reminders.releaseQueued();
        int automatic = reminders.sendAutomatic();
        if (released > 0 || automatic > 0) {
            log.info("Credit reminders: {} queued ones sent, {} automatic", released, automatic);
        }
    }
}
