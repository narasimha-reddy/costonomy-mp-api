package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.DeliveryProviderStoodDownEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryReassignListenerTest {

    @Mock private DeliveryService deliveryService;
    @Mock private TaskScheduler taskScheduler;

    private DeliveryReassignListener listener;
    private final DeliveryProviderStoodDownEvent event = new DeliveryProviderStoodDownEvent(7L, "pidge-1", "Rider cancelled");

    @BeforeEach
    void setUp() {
        listener = new DeliveryReassignListener(deliveryService, taskScheduler);
    }

    @Test
    @DisplayName("the booking runs off the webhook's thread, scheduled now, and not inline")
    void scheduledNotInline() {
        listener.onProviderStoodDown(event);

        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        verifyNoInteractions(deliveryService);
    }

    @Test
    @DisplayName("the scheduled task asks the service to reassign, naming the consignment that stood down")
    void taskReassigns() {
        listener.run(event);
        verify(deliveryService).reassignAfterProviderFailure(7L, "pidge-1", "Rider cancelled");
    }

    @Test
    @DisplayName("a failure is swallowed so the scheduler thread survives; the sweep retries it")
    void failureDoesNotEscape() {
        when(deliveryService.reassignAfterProviderFailure(any(), any(), any())).thenThrow(new IllegalStateException("down"));
        listener.run(event);
    }

    @Test
    @DisplayName("switched off, nothing is scheduled")
    void disabled() {
        ReflectionTestUtils.setField(listener, "enabled", false);
        listener.onProviderStoodDown(event);
        verifyNoInteractions(taskScheduler, deliveryService);
    }
}
