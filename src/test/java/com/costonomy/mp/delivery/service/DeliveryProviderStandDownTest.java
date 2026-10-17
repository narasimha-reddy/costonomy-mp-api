package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryProviderStoodDownEvent;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderEvent;
import com.costonomy.mp.delivery.repository.DeliveryEventStore;
import com.costonomy.mp.delivery.repository.DeliveryLocationRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * When a provider lets go of a consignment before pickup, the delivery must be handed on, not stranded.
 * The cases that matter are the ones that must NOT trigger a new booking: our own cancellation, and anything after
 * the goods are with the rider.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryProviderStandDownTest {

    @Mock private DeliveryRepository deliveries;
    @Mock private DeliveryEventStore eventStore;
    @Mock private DeliveryLocationRepository locations;
    @Mock private DeliveryTimeline timeline;
    @Mock private DeliveryOrderBridge orders;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher publisher;

    private DeliveryEventService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryEventService(deliveries, eventStore, locations, timeline, orders, auditService, publisher);
    }

    private static Delivery delivery(DeliveryStatus status) {
        var d = new Delivery();
        d.setId(7L);
        d.setStatus(status);
        d.setProviderCode("PIDGE");
        d.setProviderDeliveryId("pidge-1");
        d.setDriverName("Ravi");
        d.setAssignmentDeadline(Instant.now().plusSeconds(60));
        return d;
    }

    private static ProviderEvent event(ProviderDeliveryStatus status) {
        return new ProviderEvent("evt-" + status, status, "from provider", Instant.now());
    }

    private DeliveryProviderStoodDownEvent published() {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        return (DeliveryProviderStoodDownEvent) captor.getValue();
    }

    @Test
    @DisplayName("a rider cancelling announces that the consignment needs another courier")
    void riderCancelled() {
        var d = delivery(DeliveryStatus.DRIVER_ASSIGNED);

        assertThat(service.apply(d, event(ProviderDeliveryStatus.DRIVER_CANCELLED))).isEqualTo("APPLIED");

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DRIVER_CANCELLED);
        assertThat(d.getDriverName()).as("the cancelled rider is no longer shown").isNull();
        assertThat(d.getAssignmentDeadline()).isNull();
        var e = published();
        assertThat(e.deliveryId()).isEqualTo(7L);
        assertThat(e.providerDeliveryId()).as("names the consignment that stood down").isEqualTo("pidge-1");
    }

    @Test
    @DisplayName("a failed pickup announces it too")
    void pickupFailed() {
        var d = delivery(DeliveryStatus.DRIVER_AT_PICKUP);

        assertThat(service.apply(d, event(ProviderDeliveryStatus.PICKUP_FAILED))).isEqualTo("APPLIED");

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PICKUP_FAILED);
        published();
    }

    @Test
    @DisplayName("the provider cancelling the whole order before pickup is a stand-down, not the end of the delivery")
    void providerCancelledTheOrder() {
        for (var before : new DeliveryStatus[]{DeliveryStatus.PROVIDER_SELECTED, DeliveryStatus.DRIVER_ASSIGNED,
                DeliveryStatus.DRIVER_AT_PICKUP}) {
            org.mockito.Mockito.reset(publisher);
            var d = delivery(before);

            assertThat(service.apply(d, event(ProviderDeliveryStatus.CANCELLED))).isEqualTo("APPLIED");

            assertThat(d.getStatus()).as("from " + before).isEqualTo(DeliveryStatus.DRIVER_CANCELLED);
            assertThat(d.getStatus().isTerminal()).isFalse();
            published();
        }
    }

    @Test
    @DisplayName("our own cancellation is not mistaken for the provider's: its echo changes nothing and books nothing")
    void ourOwnCancelEchoIsIgnored() {
        var d = delivery(DeliveryStatus.CANCELLED);

        assertThat(service.apply(d, event(ProviderDeliveryStatus.CANCELLED))).isEqualTo("OUT_OF_ORDER");

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("once the goods are picked up a cancellation books nothing")
    void afterPickupNothingIsBooked() {
        var d = delivery(DeliveryStatus.PICKED_UP);

        assertThat(service.apply(d, event(ProviderDeliveryStatus.CANCELLED))).isEqualTo("IGNORED");

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PICKED_UP);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("ordinary progress announces nothing")
    void ordinaryProgressIsQuiet() {
        var d = delivery(DeliveryStatus.PROVIDER_SELECTED);

        service.apply(d, event(ProviderDeliveryStatus.DRIVER_ASSIGNED));

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DRIVER_ASSIGNED);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a cancel arriving when the delivery already stood down is not announced twice")
    void notAnnouncedTwice() {
        var d = delivery(DeliveryStatus.DRIVER_CANCELLED);

        assertThat(service.apply(d, event(ProviderDeliveryStatus.CANCELLED))).isEqualTo("OUT_OF_ORDER");

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a rider assigned again after cancelling (the provider found another) is accepted")
    void providerRecoversOnItsOwn() {
        var d = delivery(DeliveryStatus.DRIVER_CANCELLED);

        assertThat(service.apply(d, event(ProviderDeliveryStatus.DRIVER_ASSIGNED))).isEqualTo("APPLIED");
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DRIVER_ASSIGNED);
    }
}
