package com.costonomy.mp.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import jakarta.annotation.PostConstruct;
import org.hibernate.StaleStateException;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.stereotype.Component;

/**
 * Keeps an optimistic-lock loss from being reported at ERROR by Hibernate itself.
 *
 * <p>When a versioned update matches no row, Hibernate's batch executor logs
 * {@code HHH100501: Exception executing batch [StaleStateException ...]} at ERROR
 * and only then throws, so by the time our code catches the resulting
 * {@code OptimisticLockingFailureException} the ERROR line is already written. A
 * lost cancel-or-ready race, a token refreshed twice at once: expected under
 * concurrency, handled (409 or the winning outcome) and logged by the handler that
 * catches them, but each still raised an alert-grade ERROR line.
 *
 * <p>The line cannot be avoided from our code, so it is dropped here, and only
 * this one shape of it: an ERROR from {@code org.hibernate.orm.jdbc.batch} whose
 * cause is a {@link StaleStateException}. Any other failure from that logger (a
 * constraint violation, a lost connection, a batch error of any other kind) is
 * untouched and still logs at ERROR. The exception itself still propagates, so
 * an optimistic lock lost somewhere that does not handle it still surfaces through
 * {@code GlobalExceptionHandler}, at WARN, with the request id.
 */
@Component
public class ExpectedStaleStateLogFilter extends TurboFilter {

    static final String BATCH_LOGGER = "org.hibernate.orm.jdbc.batch";

    /**
     * How long after dropping a stale-state batch error on a thread the same thread's
     * {@code HHH100503: On release of batch it still contained JDBC statements} (INFO) is taken to be its
     * consequence: the failed batch is released with its statements still in it, a moment later, on the same thread.
     */
    static final long RELEASE_FOLLOWS_WITHIN_NANOS = 5_000_000_000L;

    /** When this thread last had a stale-state batch error dropped, in {@link System#nanoTime()}; consumed by the release line. */
    static final ThreadLocal<Long> DROPPED_AT = new ThreadLocal<>();

    @PostConstruct
    void install() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context
                && context.getTurboFilterList().stream().noneMatch(f -> f instanceof ExpectedStaleStateLogFilter)) {
            setName("expected-stale-state");
            start();
            context.addTurboFilter(this);
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                              Object[] params, Throwable t) {
        if (!BATCH_LOGGER.equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }
        if (level == Level.INFO) {
            // Only the release of the very batch whose stale-state error was just dropped on this thread, once. The
            // same line without one (a batch abandoned for any other reason) says something else and is kept.
            Long dropped = DROPPED_AT.get();
            if (dropped != null && format != null && format.startsWith("HHH100503")) {
                DROPPED_AT.remove();
                if (System.nanoTime() - dropped <= RELEASE_FOLLOWS_WITHIN_NANOS) {
                    return FilterReply.DENY;
                }
            }
            return FilterReply.NEUTRAL;
        }
        if (level != Level.ERROR) {
            return FilterReply.NEUTRAL;
        }
        if (causedByStaleState(t) || anyStaleState(params)) {
            DROPPED_AT.set(System.nanoTime());
            return FilterReply.DENY;
        }
        // jboss-logging may hand over the message already formatted, with the
        // exception rendered into it: recognise that form too, but only for the
        // exact HHH100501 wording, so nothing else from this logger is caught.
        if (format != null && format.startsWith("HHH100501") && format.contains("StaleStateException")) {
            DROPPED_AT.set(System.nanoTime());
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }

    private static boolean anyStaleState(Object[] params) {
        if (params == null) {
            return false;
        }
        for (Object p : params) {
            if (p instanceof Throwable throwable && causedByStaleState(throwable)) {
                return true;
            }
        }
        return false;
    }

    private static boolean causedByStaleState(Throwable t) {
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof StaleStateException) {
                return true;
            }
        }
        return false;
    }
}
