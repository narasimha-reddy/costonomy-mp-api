package com.costonomy.mp.common.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-191 addendum: a commit trigger is never lost because the lock is busy, a drain is running, or a backlog exists. */
class OutboxCommitTriggerTest {

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private OutboxCommitTrigger trigger;

    private OutboxCommitTrigger trigger() {
        trigger = new OutboxCommitTrigger(publisher, true, 20);
        return trigger;
    }

    @AfterEach
    void stop() {
        if (trigger != null) {
            trigger.stop();
        }
    }

    @Test
    void aCommitStartsOneDrain() {
        when(publisher.drainAfterCommit()).thenReturn(1);
        trigger().request();
        verify(publisher, timeout(2000).times(1)).drainAfterCommit();
    }

    @Test
    void aBusyLockIsRetriedUntilTheDrainRuns() {
        // Lock taken twice (the poll was draining), then free: the event is taken without waiting for the next poll.
        when(publisher.drainAfterCommit()).thenReturn(null, null, 1);
        trigger().request();
        verify(publisher, timeout(2000).times(3)).drainAfterCommit();
    }

    @Test
    void aLockThatStaysBusyIsRetriedOnlyABoundedNumberOfTimes() throws Exception {
        when(publisher.drainAfterCommit()).thenReturn(null);
        trigger().request();
        verify(publisher, timeout(5000).times(OutboxCommitTrigger.MAX_BUSY_RETRIES + 1)).drainAfterCommit();
        Thread.sleep(150);
        verify(publisher, times(OutboxCommitTrigger.MAX_BUSY_RETRIES + 1)).drainAfterCommit();
    }

    @Test
    void aFullBatchGoesOnAtOnceBecauseABacklogMayHideTheNewestEvent() {
        when(publisher.drainAfterCommit()).thenReturn(OutboxPublisher.BATCH_SIZE, OutboxPublisher.BATCH_SIZE, 3);
        trigger().request();
        verify(publisher, timeout(2000).times(3)).drainAfterCommit();
    }

    @Test
    void aCommitDuringARunningDrainQueuesOneMore() throws Exception {
        var inDrain = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(publisher.drainAfterCommit()).thenAnswer(inv -> {
            if (inDrain.getCount() > 0) {
                inDrain.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            return 1;
        });
        trigger().request();
        assertThat(inDrain.await(2, TimeUnit.SECONDS)).isTrue();
        // Three commits while the first drain runs: coalesced into exactly one further drain.
        trigger.request();
        trigger.request();
        trigger.request();
        release.countDown();
        verify(publisher, timeout(2000).times(2)).drainAfterCommit();
        Thread.sleep(150);
        verify(publisher, times(2)).drainAfterCommit();
    }

    @Test
    void aFailingDrainDoesNotStopTheNextOne() {
        when(publisher.drainAfterCommit()).thenThrow(new IllegalStateException("boom")).thenReturn(1);
        trigger().request();
        verify(publisher, timeout(2000).times(1)).drainAfterCommit();
        // The failure is logged only; a later commit still drains.
        Mockito.reset(publisher);
        when(publisher.drainAfterCommit()).thenReturn(1);
        trigger.request();
        verify(publisher, timeout(2000).times(1)).drainAfterCommit();
    }

    @Test
    void disabledNeverDrains() throws Exception {
        trigger = new OutboxCommitTrigger(publisher, false, 20);
        trigger.requestAfterCommit();
        Thread.sleep(100);
        verify(publisher, times(0)).drainAfterCommit();
    }
}
