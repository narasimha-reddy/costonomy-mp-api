package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodDeliveryReconciliationJobTest {

    @Mock private FoodDeliveryRepository deliveries;
    @Mock private DeliveryProviderRegistry registry;
    @Mock private FoodDeliveryEventService eventService;
    @Mock private DeliveryProvider provider;

    private FoodDeliveryReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new FoodDeliveryReconciliationJob(deliveries, registry, eventService);
        ReflectionTestUtils.setField(job, "enabled", true);
    }

    @Test
    @DisplayName("polls and releases claimed due delivery")
    void pollsAndReleasesClaimedDueDelivery() {
        Instant now = Instant.now();
        Long deliveryId = 101L;

        when(deliveries.findDueDeliveryIdsForPolling(anyCollection(), any(Instant.class), any(Instant.class), eq(50)))
                .thenReturn(List.of(deliveryId));
        when(deliveries.claimDeliveryForPoll(eq(deliveryId), eq(now), any(Instant.class), anyString()))
                .thenReturn(1);

        var delivery = new FoodDelivery();
        delivery.setStatus(FoodDeliveryStatus.DRIVER_ASSIGNED);
        delivery.setProviderCode("BORZO");
        delivery.setProviderDeliveryId("borzo-101");
        ReflectionTestUtils.setField(delivery, "id", deliveryId);

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(registry.adapter("BORZO")).thenReturn(provider);
        when(provider.supportsTracking()).thenReturn(true);

        var provDelivery = new DeliveryProvider.ProviderDelivery(
                "borzo-101",
                DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED,
                "Rider Ram",
                "+919876543210",
                "Bike",
                15,
                now,
                null,
                null,
                List.of()
        );
        when(provider.status("borzo-101")).thenReturn(provDelivery);

        var loc = new DeliveryProvider.Location(
                new BigDecimal("17.4326"),
                new BigDecimal("78.4071"),
                180.0,
                30.0,
                now
        );
        when(provider.location("borzo-101")).thenReturn(loc);

        int count = job.runReconciliation(now);
        assertThat(count).isEqualTo(1);

        verify(eventService).apply(eq(delivery), eq("BORZO"), eq("borzo-101"), any(DeliveryProvider.ProviderEvent.class), isNull());
        verify(eventService).recordDriver(eq(delivery), eq("BORZO"), eq("borzo-101"), eq("Rider Ram"), eq("+919876543210"), eq("Bike"));
        verify(eventService).recordLocation(eq(delivery), eq("BORZO"), eq("borzo-101"),
                eq(new BigDecimal("17.4326")), eq(new BigDecimal("78.4071")), eq(180.0), eq(30.0), any(Instant.class));
        verify(deliveries).releaseDeliveryAfterPoll(eq(deliveryId), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("skips delivery if lease claim failed")
    void skipsDeliveryIfClaimFailed() {
        Instant now = Instant.now();
        Long deliveryId = 102L;

        when(deliveries.findDueDeliveryIdsForPolling(anyCollection(), any(Instant.class), any(Instant.class), eq(50)))
                .thenReturn(List.of(deliveryId));
        when(deliveries.claimDeliveryForPoll(eq(deliveryId), eq(now), any(Instant.class), anyString()))
                .thenReturn(0);

        int count = job.runReconciliation(now);
        assertThat(count).isEqualTo(0);

        verify(deliveries, never()).findById(anyLong());
        verify(deliveries, never()).releaseDeliveryAfterPoll(anyLong(), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("disabled job does not query repository")
    void disabledJobDoesNotQueryRepository() {
        ReflectionTestUtils.setField(job, "enabled", false);
        int count = job.runReconciliation(Instant.now());
        assertThat(count).isEqualTo(0);
        verifyNoInteractions(deliveries);
    }
}
