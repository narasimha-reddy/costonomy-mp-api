package com.costonomy.mp.wallet.invoice.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Tries again the bills whose reading did not finish (D-113). Idempotent and locked, like the other jobs. */
@Component
@RequiredArgsConstructor
public class InvoiceJobs {

    static final int BATCH = 50;

    private final InvoiceReadingService reading;

    @Scheduled(fixedDelayString = "${costonomy.mp.invoices.retry-interval:PT2M}")
    @SchedulerLock(name = "wallet-invoice-retry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void retry() {
        reading.retryDue(BATCH);
    }
}
