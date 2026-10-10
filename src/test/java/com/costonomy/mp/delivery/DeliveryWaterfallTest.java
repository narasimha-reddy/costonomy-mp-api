package com.costonomy.mp.delivery;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryBookedEvent;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.delivery.service.DeliveryWaterfallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryWaterfallTest {

    @Mock private DeliveryRepository deliveries;
    @Mock private DeliveryService deliveryService;
    @Mock private AuditService auditService;
    @Mock private TaskScheduler taskScheduler;

    private DeliveryWaterfallService waterfallService;

    @BeforeEach
    void setUp() {
        waterfallService = new DeliveryWaterfallService(deliveries, deliveryService, auditService, taskScheduler);
    }

    @Test
    void onDeliveryBookedSchedulesOneShotTaskAtDeadline() {
        Instant deadline = Instant.now().plusSeconds(180);
        var event = new DeliveryBookedEvent(42L, deadline);

        waterfallService.onDeliveryBooked(event);

        verify(taskScheduler).schedule(any(Runnable.class), eq(deadline));
    }

    @Test
    void cascadeUnassignedCascadesIfDeadlinePassed() {
        Long deliveryId = 101L;
        var delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
        delivery.setAssignmentDeadline(Instant.now().minusSeconds(10));
        delivery.setProviderCode("PIDGE");

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));

        boolean result = waterfallService.cascadeUnassigned(deliveryId);

        assertThat(result).isTrue();
        verify(deliveryService).reassign(eq(null), eq(deliveryId), contains("Unassigned driver timeout"));
    }

    @Test
    void cascadeUnassignedDoesNothingIfDriverAlreadyAssigned() {
        Long deliveryId = 102L;
        var delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED); // Already assigned!
        delivery.setAssignmentDeadline(null);

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));

        boolean result = waterfallService.cascadeUnassigned(deliveryId);

        assertThat(result).isFalse();
        verify(deliveryService, never()).reassign(any(), any(), any());
    }
}
