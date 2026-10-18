package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-200: when the sandbox auto-advance job moves a delivery, judged on a clock the test controls. */
class PidgeSandboxAutoAdvanceJobTest {

    private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
    private final PidgeSandboxService sandbox = mock(PidgeSandboxService.class);
    private final PidgeProperties properties = new PidgeProperties();
    private final MovableClock clock = new MovableClock();
    private final PidgeSandboxAutoAdvanceJob job =
            new PidgeSandboxAutoAdvanceJob(deliveries, sandbox, properties, clock);
    private final Delivery delivery = new Delivery();

    @BeforeEach
    void setUp() {
        properties.setSandbox(true);
        properties.setSandboxAutoAdvance(Duration.ofSeconds(30));
        delivery.setId(7L);
        delivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
        when(deliveries.findByProviderCodeAndModeAndProviderDeliveryIdIsNotNullAndStatusInAndCreatedAtAfter(
                eq("PIDGE"), any(), anyList(), any())).thenAnswer(i -> List.of(delivery));
        when(sandbox.advanceAsSystem(7L)).thenReturn(true);
    }

    @Test
    void notMovedBeforeTheIntervalThenMovedOnce() {
        job.runOnce();
        clock.advance(29);
        job.runOnce();
        verify(sandbox, never()).advanceAsSystem(anyLong());

        clock.advance(1);
        job.runOnce();
        verify(sandbox, times(1)).advanceAsSystem(7L);

        // The delivery has not changed status in the test, but the wait restarts rather than firing every sweep.
        clock.advance(5);
        job.runOnce();
        verify(sandbox, times(1)).advanceAsSystem(7L);
    }

    @Test
    void theTimerRestartsOnTheNewStatus() {
        job.runOnce();
        clock.advance(30);
        job.runOnce();
        verify(sandbox, times(1)).advanceAsSystem(7L);

        delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
        clock.advance(5);
        job.runOnce();
        clock.advance(29);
        job.runOnce();
        verify(sandbox, times(1)).advanceAsSystem(7L);

        clock.advance(1);
        job.runOnce();
        verify(sandbox, times(2)).advanceAsSystem(7L);
    }

    @Test
    void aDeliveryThatLeavesTheListIsForgotten() {
        job.runOnce();
        clock.advance(20);
        when(deliveries.findByProviderCodeAndModeAndProviderDeliveryIdIsNotNullAndStatusInAndCreatedAtAfter(
                eq("PIDGE"), any(), anyList(), any())).thenReturn(List.of());
        job.runOnce();
        when(deliveries.findByProviderCodeAndModeAndProviderDeliveryIdIsNotNullAndStatusInAndCreatedAtAfter(
                eq("PIDGE"), any(), anyList(), any())).thenReturn(List.of(delivery));

        // Back after 20s away: the timer starts over, so 30s from the first sight is not enough.
        clock.advance(20);
        job.runOnce();
        clock.advance(29);
        job.runOnce();
        verify(sandbox, never()).advanceAsSystem(anyLong());
    }

    @Test
    void oneFailureDoesNotStopTheOthers() {
        var other = new Delivery();
        other.setId(8L);
        other.setStatus(DeliveryStatus.PICKED_UP);
        when(deliveries.findByProviderCodeAndModeAndProviderDeliveryIdIsNotNullAndStatusInAndCreatedAtAfter(
                eq("PIDGE"), any(), anyList(), any())).thenReturn(List.of(delivery, other));
        when(sandbox.advanceAsSystem(7L)).thenThrow(new IllegalStateException("sandbox down"));

        job.runOnce();
        clock.advance(30);
        job.runOnce();

        verify(sandbox).advanceAsSystem(7L);
        verify(sandbox).advanceAsSystem(8L);
    }

    @Test
    void nothingHappensWhenInactive() {
        properties.setSandboxAutoAdvance(null);
        job.runOnce();
        clock.advance(3600);
        job.runOnce();

        properties.setSandboxAutoAdvance(Duration.ofSeconds(30));
        properties.setSandbox(false);
        job.runOnce();
        clock.advance(3600);
        job.runOnce();

        properties.setSandbox(true);
        properties.setSandboxAutoAdvance(Duration.ZERO);
        job.runOnce();
        clock.advance(3600);
        job.runOnce();

        verify(sandbox, never()).advanceAsSystem(anyLong());
        verify(deliveries, never())
                .findByProviderCodeAndModeAndProviderDeliveryIdIsNotNullAndStatusInAndCreatedAtAfter(
                        any(), any(), anyList(), any());
    }

    /** A clock that only moves when the test says so. */
    static class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T10:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
