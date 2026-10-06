package com.costonomy.mp.credit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** The reminder job (D-142): releases reminders that waited for the morning, then sends the automatic ones. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditReminderJobs {

    private final CreditAutoReminderService reminders;

    @Scheduled(fixedDelayString = "${costonomy.mp.credit.reminder-interval:PT1H}")
    @SchedulerLock(name = "credit-reminders", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void runReminders() {
        int released = reminders.releaseQueued();
        int automatic = reminders.sendAutomatic();
        if (released > 0 || automatic > 0) {
            log.info("Credit reminders: {} queued ones sent, {} automatic", released, automatic);
        }
    }
}
