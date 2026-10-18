package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryJobsTest {

    @Mock private DeliveryRepository deliveries;
    @Mock private DeliveryProviderRegistry registry;
    @Mock private DeliveryEventService eventService;
    @Mock private DeliveryProvider adapter;

    private DeliveryJobs jobs;

    @BeforeEach
    void setUp() {
        jobs = new DeliveryJobs(deliveries, registry, eventService);
    }

    @Test
    @DisplayName("pollActiveDeliveries queries bounded batch, claims atomically, and releases claim outside transaction")
    void boundedBatchPollingWithAtomicClaimAndRelease() {
        long deliveryId = 100L;
        when(deliveries.findDueDeliveryIdsForPolling(any(Instant.class), eq(50)))
                .thenReturn(List.of(deliveryId));
        when(deliveries.claimDeliveryForPoll(eq(deliveryId), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(1);

        var delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setMode(DeliveryMode.COSTONOMY);
        delivery.setProviderCode("MOCK_EXPRESS");
        delivery.setProviderDeliveryId("EXP-001");
        delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(adapter);
        var status = new DeliveryProvider.ProviderDelivery(
                "EXP-001", DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED, "Suresh", "+919876543210",
                "Hero Splendor", 15, null, null, null, List.of());
        when(adapter.status("EXP-001")).thenReturn(status);
        when(adapter.supportsTracking()).thenReturn(false);
        when(eventService.applyProviderSnapshot(deliveryId, status)).thenReturn(true);

        jobs.pollActiveDeliveries();

        verify(deliveries).claimDeliveryForPoll(eq(deliveryId), any(Instant.class), any(Instant.class), anyString());
        verify(adapter).status("EXP-001");
        verify(eventService).applyProviderSnapshot(deliveryId, status);
        verify(deliveries).releaseDeliveryAfterPoll(eq(deliveryId), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("transient provider error releases claim without blocking batch or throwing exception")
    void providerErrorReleasesClaimSafely() {
        long deliveryId = 101L;
        when(deliveries.findDueDeliveryIdsForPolling(any(Instant.class), eq(50)))
                .thenReturn(List.of(deliveryId));
        when(deliveries.claimDeliveryForPoll(eq(deliveryId), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(1);

        var delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setMode(DeliveryMode.COSTONOMY);
        delivery.setProviderCode("MOCK_SAVER");
        delivery.setProviderDeliveryId("SAV-002");
        delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(registry.adapter("MOCK_SAVER")).thenReturn(adapter);
        when(adapter.status("SAV-002")).thenThrow(new DeliveryProviderException("MOCK_SAVER", "Timeout", true));

        jobs.pollActiveDeliveries();

        verify(adapter).status("SAV-002");
        verify(deliveries).releaseDeliveryAfterPoll(eq(deliveryId), anyString(), any(Instant.class));
    }
}
