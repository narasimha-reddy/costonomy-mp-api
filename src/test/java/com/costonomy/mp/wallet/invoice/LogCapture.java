package com.costonomy.mp.wallet.invoice;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures everything logged, by any logger, at every level down to TRACE, while it is open (D-115, M7). Used around the
 * real call sites so a token or password written to any log at any level is caught.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Logger ours = (Logger) LoggerFactory.getLogger("com.costonomy");
    private final Level rootBefore = root.getLevel();
    private final Level oursBefore = ours.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture() {
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        ours.setLevel(Level.TRACE);
    }

    /** Every message and every exception message in the chain, formatted. */
    public List<String> lines() {
        var out = new ArrayList<String>();
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            out.add(event.getLoggerName() + " " + event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                out.add(String.valueOf(t.getMessage()));
            }
        }
        return out;
    }

    public void assertNoneContain(Collection<String> secrets) {
        assertThat(secrets).isNotEmpty();
        for (String line : lines()) {
            for (String secret : secrets) {
                assertThat(line).describedAs("a log line").doesNotContain(secret);
            }
        }
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(rootBefore);
        ours.setLevel(oursBefore);
    }
}
