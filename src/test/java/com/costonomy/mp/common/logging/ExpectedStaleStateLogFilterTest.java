package com.costonomy.mp.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.spi.FilterReply;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** What the filter drops of a lost optimistic lock, and only that. */
class ExpectedStaleStateLogFilterTest {

    private static final String RELEASE = "HHH100503: On release of batch it still contained JDBC statements";

    private final ExpectedStaleStateLogFilter filter = new ExpectedStaleStateLogFilter();
    private final Logger batch = (Logger) LoggerFactory.getLogger(ExpectedStaleStateLogFilter.BATCH_LOGGER);

    /** The dropped line is remembered per thread: leave none for the next test. */
    @AfterEach
    void forget() {
        filter.decide(null, batch, Level.INFO, RELEASE, null, null);
    }

    private FilterReply staleError() {
        return filter.decide(null, batch, Level.ERROR, "HHH100501: Exception executing batch [{}]",
                new Object[]{new StaleStateException("Batch update returned unexpected row count")}, null);
    }

    @Test
    @DisplayName("F4: the release line of a batch whose stale-state error was just dropped on this thread is dropped, once")
    void theReleaseOfTheFailedBatchIsDroppedOnce() {
        assertThat(staleError()).isEqualTo(FilterReply.DENY);

        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.DENY);
        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null))
                .describedAs("a second one is not this batch's").isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("F4: the same line with no stale-state error before it is kept: a batch abandoned for another reason says something else")
    void aReleaseWithoutAStaleStateErrorIsKept() {
        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("F4: another thread's release line is kept, and so is another line of the same logger at INFO")
    void otherThreadsAndOtherLinesAreKept() throws Exception {
        assertThat(staleError()).isEqualTo(FilterReply.DENY);
        var other = new FilterReply[1];
        var thread = new Thread(() -> other[0] = filter.decide(null, batch, Level.INFO, RELEASE, null, null));
        thread.start();
        thread.join();

        assertThat(other[0]).isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(null, batch, Level.INFO, "HHH000000: something else", null, null)).isEqualTo(FilterReply.NEUTRAL);
        // (and this thread's own release line is still dropped, its error being the one remembered)
        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.DENY);
    }

    @Test
    @DisplayName("F4: the release line of another logger, and a batch error that is not a stale state, are untouched")
    void otherLoggersAndOtherErrorsAreUntouched() {
        var elsewhere = (Logger) LoggerFactory.getLogger("some.other.logger");
        assertThat(staleError()).isEqualTo(FilterReply.DENY);

        assertThat(filter.decide(null, elsewhere, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(null, batch, Level.ERROR, "HHH100501: Exception executing batch [{}]",
                new Object[]{new IllegalStateException("constraint")}, null)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("F4: the release line more than five seconds after the dropped error is kept (and forgotten); within the window it is dropped")
    void theFiveSecondWindowIsKept() {
        // Dropped long ago: the release line is not this batch's consequence, and is logged.
        ExpectedStaleStateLogFilter.DROPPED_AT.set(System.nanoTime() - ExpectedStaleStateLogFilter.RELEASE_FOLLOWS_WITHIN_NANOS
                - 1_000_000_000L);
        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(ExpectedStaleStateLogFilter.DROPPED_AT.get()).describedAs("the marker is consumed either way").isNull();
        // Dropped a moment ago: dropped too.
        ExpectedStaleStateLogFilter.DROPPED_AT.set(System.nanoTime() - 1_000_000_000L);
        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.DENY);
        // And a late one does not turn the next fresh error's release into a kept line.
        ExpectedStaleStateLogFilter.DROPPED_AT.set(System.nanoTime() - 2 * ExpectedStaleStateLogFilter.RELEASE_FOLLOWS_WITHIN_NANOS);
        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(staleError()).isEqualTo(FilterReply.DENY);
        assertThat(filter.decide(null, batch, Level.INFO, RELEASE, null, null)).isEqualTo(FilterReply.DENY);
    }
}
