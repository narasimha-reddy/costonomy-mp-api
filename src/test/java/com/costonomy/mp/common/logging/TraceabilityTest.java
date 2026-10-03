package com.costonomy.mp.common.logging;

import com.costonomy.mp.common.web.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** D-100: job runs are correlated, and business ids ride on every line in scope. */
class TraceabilityTest {

    @AfterEach
    void clean() {
        MDC.clear();
        RequestContext.clear();
    }

    /** Stands in for a @Scheduled method. */
    public static class Jobs {
        final List<String> seen = new CopyOnWriteArrayList<>();

        public void reconcileStale() {
            seen.add(MDC.get("requestId") + "|" + RequestContext.requestId());
        }
    }

    @Test
    @DisplayName("each job run gets its own id, in the logs and the audit context alike, and leaves none behind")
    void jobRunsAreCorrelated() throws Exception {
        var jobs = new Jobs();
        Runnable run = CorrelatedTaskScheduler.correlated(
                new ScheduledMethodRunnable(jobs, Jobs.class.getMethod("reconcileStale")));

        run.run();
        run.run();

        assertThat(jobs.seen).hasSize(2);
        for (String pair : jobs.seen) {
            String[] ids = pair.split("\\|");
            assertThat(ids[0]).matches("job-reconcileStale-[0-9a-f]{8}");
            // The same id where the audit trail and outbox read it.
            assertThat(ids[1]).isEqualTo(ids[0]);
        }
        assertThat(jobs.seen.get(0)).isNotEqualTo(jobs.seen.get(1));
        // A pooled thread must not carry this run's id into the next job's lines.
        assertThat(MDC.get("requestId")).isNull();
        assertThat(RequestContext.requestId()).isEqualTo("-");
    }

    @Test
    @DisplayName("the real scheduler correlates what it runs")
    void schedulerCorrelates() throws Exception {
        var scheduler = new CorrelatedTaskScheduler();
        scheduler.initialize();
        try {
            var jobs = new Jobs();
            scheduler.schedule(new ScheduledMethodRunnable(jobs, Jobs.class.getMethod("reconcileStale")),
                    Instant.now()).get(5, TimeUnit.SECONDS);
            assertThat(jobs.seen).singleElement().asString().startsWith("job-reconcileStale-");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("trace scopes nest, drop missing ids, and restore what was there")
    void scopesNest() {
        try (var outer = TraceScope.of("payment", 152, "order", 154, "rzp_payment", null)) {
            assertThat(MDC.get("trace")).isEqualTo(" payment=152 order=154");
            try (var inner = TraceScope.of("refund", 9)) {
                assertThat(MDC.get("trace")).isEqualTo(" payment=152 order=154 refund=9");
            }
            assertThat(MDC.get("trace")).isEqualTo(" payment=152 order=154");
        }
        assertThat(MDC.get("trace")).isNull();
    }

    @Test
    @DisplayName("an id cannot break out of its line")
    void idsCannotForgeLines() {
        // Webhook ids come from outside. A newline in one would let the sender
        // write a line of their own into our logs.
        try (var scope = TraceScope.of("rzp_event", "evt_1\nINFO forged line")) {
            assertThat(MDC.get("trace")).doesNotContain("\n").isEqualTo(" rzp_event=evt_1_INFO_forged_line");
        }
    }
}
