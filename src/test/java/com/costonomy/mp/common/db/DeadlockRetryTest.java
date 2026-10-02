package com.costonomy.mp.common.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A transaction that lost a deadlock is run once more, and only that, and only when it owns its transaction (D-110). */
class DeadlockRetryTest {

    /** What InnoDB's victim looks like once Hibernate and Spring have wrapped it. */
    private static RuntimeException aDeadlock() {
        return new CannotAcquireLockException("could not execute statement",
                new SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213));
    }

    @AfterEach
    void noTransactionLeftBehind() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("a deadlock loser is run once more, and its second run's answer is the answer")
    void aDeadlockLoserIsRunOnceMore() {
        var runs = new AtomicInteger();

        String answer = DeadlockRetry.once(() -> {
            if (runs.incrementAndGet() == 1) {
                throw aDeadlock();
            }
            return "done";
        });

        assertThat(answer).isEqualTo("done");
        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("only once: a second deadlock is a real problem and is thrown")
    void onlyOnce() {
        var runs = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.once(() -> {
            runs.incrementAndGet();
            throw aDeadlock();
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("anything that is not a deadlock is thrown at once and not run again")
    void otherFailuresAreNotRetried() {
        var runs = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.once(() -> {
            runs.incrementAndGet();
            throw new DataIntegrityViolationException("Duplicate entry", new SQLException("dup", "23000", 1062));
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> DeadlockRetry.once(() -> {
            runs.incrementAndGet();
            // A lock wait timeout is not a deadlock: running it again would wait as long again.
            throw new CannotAcquireLockException("Lock wait timeout exceeded", new SQLException("timeout", "HY000", 1205));
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("inside a caller's transaction nothing is retried: the rollback took more than this call's work")
    void notInsideACallersTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var runs = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.once(() -> {
            runs.incrementAndGet();
            throw aDeadlock();
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(runs).hasValue(1);
    }
}
