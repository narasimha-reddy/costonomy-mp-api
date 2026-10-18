package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryAssignmentSweepJob;
import com.costonomy.mp.delivery.service.DeliveryWaterfallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The durable half of the 3-minute assignment timeout: a booking whose in-memory timer was lost to a restart is still
 * handed to the waterfall.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryAssignmentSweepJobTest {

    @Mock private DeliveryRepository deliveries;
    @Mock private com.costonomy.mp.delivery.repository.DeliveryProviderAttemptRepository attempts;
    @Mock private com.costonomy.mp.delivery.service.DeliveryBookingService bookingService;
    @Mock private DeliveryWaterfallService waterfall;
    @Mock private com.costonomy.mp.delivery.service.DeliveryService deliveryService;

    private DeliveryAssignmentSweepJob job;
    private final Instant now = Instant.parse("2026-10-17T12:00:00Z");

    @BeforeEach
    void setUp() {
        job = new DeliveryAssignmentSweepJob(deliveries, attempts, bookingService, waterfall, deliveryService);
    }

    private static Delivery delivery(long id) {
        var d = new Delivery();
        d.setId(id);
        return d;
    }

    @Test
    @DisplayName("bookings past their deadline are handed to the waterfall")
    void overdueBookingsAreCascaded() {
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class)))
                .thenReturn(List.of(delivery(1), delivery(2)));
        when(waterfall.cascadeUnassigned(1L)).thenReturn(true);
        when(waterfall.cascadeUnassigned(2L)).thenReturn(false);

        assertThat(job.runOnce(now)).as("only the one that actually moved is counted").isEqualTo(1);

        verify(waterfall).cascadeUnassigned(1L);
        verify(waterfall).cascadeUnassigned(2L);
    }

    @Test
    @DisplayName("only PROVIDER_SELECTED is swept, and only past the deadline plus the grace")
    void queryIsStatusAndGrace() {
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());

        job.runOnce(now);

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(deliveries).findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            org.mockito.ArgumentMatchers.eq(DeliveryStatus.PROVIDER_SELECTED), cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getValue()).as("30s grace so a live timer wins").isEqualTo(now.minusSeconds(30));
        verify(waterfall, never()).cascadeUnassigned(anyLong());
    }

    @Test
    @DisplayName("one delivery failing does not stop the rest")
    void oneFailureDoesNotStopTheSweep() {
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class)))
                .thenReturn(List.of(delivery(1), delivery(2), delivery(3)));
        when(waterfall.cascadeUnassigned(1L)).thenThrow(new IllegalStateException("provider down"));
        when(waterfall.cascadeUnassigned(2L)).thenReturn(true);
        when(waterfall.cascadeUnassigned(3L)).thenReturn(true);

        assertThat(job.runOnce(now)).isEqualTo(2);
        verify(waterfall).cascadeUnassigned(3L);
    }

    @Test
    @DisplayName("a sweep is capped at the batch size")
    void batchIsCapped() {
        ReflectionTestUtils.setField(job, "batch", 2);
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class)))
            .thenReturn(List.of(delivery(1), delivery(2)));
        when(waterfall.cascadeUnassigned(anyLong())).thenReturn(true);

        assertThat(job.runOnce(now)).isEqualTo(2);
        verify(deliveries).findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            eq(DeliveryStatus.PROVIDER_SELECTED), any(),
            eq(org.springframework.data.domain.PageRequest.of(0, 2)));
        verify(waterfall, never()).cascadeUnassigned(3L);
    }

    @Test
    @DisplayName("switched off, it does nothing")
    void disabled() {
        ReflectionTestUtils.setField(job, "enabled", false);

        assertThat(job.runOnce(now)).isZero();
        verifyNoInteractions(deliveries, waterfall);
    }

    // --- deliveries a provider let go of (cancelled rider, failed pickup) that nothing else would pick up ---

    private static Delivery stoodDown(long id, DeliveryStatus status, Instant updatedAt) {
        var d = delivery(id);
        d.setStatus(status);
        d.setUpdatedAt(updatedAt);
        return d;
    }

    @Test
    @DisplayName("a delivery left in DRIVER_CANCELLED or PICKUP_FAILED is given another courier")
    void stoodDownDeliveriesAreRescued() {
        var old = now.minusSeconds(120);
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(deliveries.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(), any(), any(Pageable.class)))
                .thenReturn(List.of(stoodDown(1, DeliveryStatus.DRIVER_CANCELLED, old),
                        stoodDown(2, DeliveryStatus.PICKUP_FAILED, old)));
        when(deliveryService.reassignAfterProviderFailure(eq(1L), isNull(), any())).thenReturn(true);
        when(deliveryService.reassignAfterProviderFailure(eq(2L), isNull(), any())).thenReturn(false);

        assertThat(job.runOnce(now)).as("only the one that got a courier is counted").isEqualTo(1);
        verify(deliveryService).reassignAfterProviderFailure(eq(2L), isNull(), any());
    }

    @Test
    @DisplayName("one that just stood down is left to the event listener for the grace period")
    void freshStandDownIsLeftAlone() {
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(deliveries.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());

        assertThat(job.runOnce(now)).isZero();
        verify(deliveries).findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
                eq(List.of(DeliveryStatus.DRIVER_CANCELLED, DeliveryStatus.PICKUP_FAILED)),
                eq(now.minusSeconds(30)), any(Pageable.class));
        verifyNoInteractions(deliveryService);
    }

    @Test
    @DisplayName("a failing rescue does not stop the next")
    void rescueFailureDoesNotStopTheRest() {
        var old = now.minusSeconds(120);
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(deliveries.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of(
                stoodDown(1, DeliveryStatus.DRIVER_CANCELLED, old), stoodDown(2, DeliveryStatus.DRIVER_CANCELLED, old)));
        when(deliveryService.reassignAfterProviderFailure(eq(1L), isNull(), any()))
                .thenThrow(new IllegalStateException("provider down"));
        when(deliveryService.reassignAfterProviderFailure(eq(2L), isNull(), any())).thenReturn(true);

        assertThat(job.runOnce(now)).isEqualTo(1);
    }

    @Test
    @DisplayName("stale pending attempts are rescued by the booking service")
    void stalePendingAttemptsAreRescued() {
        when(deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(deliveries.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());

        var staleAttempt = new com.costonomy.mp.delivery.domain.DeliveryProviderAttempt();
        staleAttempt.setId(99L);
        staleAttempt.setOutcome("PENDING");
        staleAttempt.setStartedAt(now.minusSeconds(300));
        when(attempts.findByOutcomeAndStartedAtBeforeOrderByStartedAtAscIdAsc(
            eq("PENDING"), any(), any(Pageable.class))).thenReturn(List.of(staleAttempt));

        assertThat(job.runOnce(now)).isEqualTo(1);
        verify(bookingService).recoverStalePendingAttempt(99L);
    }
}
