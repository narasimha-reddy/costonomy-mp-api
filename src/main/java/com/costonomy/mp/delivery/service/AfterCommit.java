package com.costonomy.mp.delivery.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs follow-up work once the caller's transaction has committed, or at once if there is none (D-204).
 *
 * <p>Used to keep the courier payable in line with a delivery without letting it into the delivery's transaction: the
 * payable reads committed state, and a failure in it can neither roll back nor poison the delivery update. The sweep
 * repairs anything that did not run.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(String what, Runnable work) {
        Runnable safe = () -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.error("Follow-up after commit failed ({}); the sweep will repair it", what, ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
