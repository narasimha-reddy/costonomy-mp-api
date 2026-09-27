package com.costonomy.mp.delivery;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.delivery.service.DeliveryDispatchListener;
import com.costonomy.mp.delivery.service.DeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryDispatchListenerTest {

    @Mock
    private DeliveryService deliveryService;

    private DeliveryDispatchListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeliveryDispatchListener(deliveryService);
        ReflectionTestUtils.setField(listener, "autoDispatchEnabled", true);
    }

    @Test
    void triggersAutoDispatchOnSupplierOrderReady() {
        var envelope = new OutboxPublisher.DomainEventEnvelope(
                "evt-1",
                "SupplierOrderReady",
                "SUPPLIER_ORDER",
                42L,
                1,
                "{}",
                10L,
                "corr-1",
                Instant.now());

        listener.onDomainEvent(envelope);

        verify(deliveryService, times(1)).autoDispatch(42L);
    }

    @Test
    void ignoresUnrelatedEvents() {
        var envelope = new OutboxPublisher.DomainEventEnvelope(
                "evt-2",
                "PaymentCompleted",
                "PAYMENT",
                99L,
                1,
                "{}",
                10L,
                "corr-2",
                Instant.now());

        listener.onDomainEvent(envelope);

        verify(deliveryService, never()).autoDispatch(anyLong());
    }

    @Test
    void respectsDisabledFlag() {
        ReflectionTestUtils.setField(listener, "autoDispatchEnabled", false);

        var envelope = new OutboxPublisher.DomainEventEnvelope(
                "evt-3",
                "SupplierOrderReady",
                "SUPPLIER_ORDER",
                42L,
                1,
                "{}",
                10L,
                "corr-3",
                Instant.now());

        listener.onDomainEvent(envelope);

        verify(deliveryService, never()).autoDispatch(anyLong());
    }
}
