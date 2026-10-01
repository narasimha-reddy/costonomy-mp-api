package com.costonomy.mp.common.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs a self-contained transaction once more if the database rolled it back as the loser of a deadlock.
 *
 * <p>A deadlock is not a failure of the request: InnoDB picks one of the two transactions, rolls it back whole, and
 * the same statements run a moment later succeed. For an approval that moves money in one transaction (a dispute
 * refund charges the supplier and credits the wallet) the rollback is safe, but telling a supplier "try again"
 * for something the server can do itself is not.
 *
 * <p><b>Only where nothing else is in the transaction.</b> The retry is made by whoever <em>starts</em> the
 * transaction. Inside a caller's larger transaction a rollback has taken more than this call's work with it, and
 * running it again would carry on as though the earlier statements had happened: there the deadlock is left to
 * propagate. And only once: a second deadlock is a real problem, not a moment's bad luck.
 */
@Slf4j
public final class DeadlockRetry {

    private DeadlockRetry() {
    }

    /** MySQL's SQLSTATE for a deadlock (and for a serialization failure): what InnoDB reports when it picks a loser. */
    private static final String DEADLOCK_SQL_STATE = "40001";

    /** Run {@code transaction}, which must begin and end its own transaction; once more if it lost a deadlock. */
    public static <T> T once(Supplier<T> transaction) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return transaction.get();
        }
        try {
            return transaction.get();
        } catch (RuntimeException ex) {
            if (!isDeadlock(ex)) {
                throw ex;
            }
            log.warn("Transaction lost a deadlock and was rolled back; running it once more: {}", ex.getMessage());
            try {
                // A short, uneven pause, so the two that collided do not collide again in step.
                Thread.sleep(ThreadLocalRandom.current().nextLong(10, 60));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ex;
            }
            return transaction.get();
        }
    }

    /** Whether the database chose this as the loser of a deadlock, however the layers above have wrapped it. */
    public static boolean isDeadlock(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DeadlockLoserDataAccessException
                    || (t instanceof SQLException sql && DEADLOCK_SQL_STATE.equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
