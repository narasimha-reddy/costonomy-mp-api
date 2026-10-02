package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.*;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryLedgerRepository;
import com.costonomy.mp.delivery.repository.DeliveryProviderAttemptRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryBookingServiceTest {

    @Mock
    private DeliveryRepository deliveries;

    @Mock
    private DeliveryProviderAttemptRepository attempts;

    @Mock
    private DeliveryQuotingService quoting;

    @Mock
    private DeliveryProviderRegistry registry;

    @Mock
    private DeliveryTimeline timeline;

    @Mock
    private AuditService auditService;

    @Mock
    private DeliveryLedgerRepository ledger;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private DeliveryProvider providerAdapter;

    private DeliveryBookingService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryBookingService(
                deliveries,
                attempts,
                quoting,
                registry,
                timeline,
                auditService,
                ledger,
                eventPublisher
        );
        lenient().when(attempts.save(any(DeliveryProviderAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Delivery createDelivery(Long id, VehicleType vehicleType) {
        Delivery delivery = new Delivery();
        delivery.setId(id);
        delivery.setSupplierOrderId(200L);
        delivery.setVehicleType(vehicleType);
        delivery.setWeightKg(BigDecimal.valueOf(10.0));
        delivery.setPickupAddress("Warehouse, Indiranagar");
        delivery.setPickupLatitude(BigDecimal.valueOf(12.9716));
        delivery.setPickupLongitude(BigDecimal.valueOf(77.5946));
        delivery.setPickupContactName("Store Mgr");
        delivery.setPickupContactPhone("+919876543210");
        delivery.setDropAddress("Restaurant, Koramangala");
        delivery.setDropLatitude(BigDecimal.valueOf(12.9352));
        delivery.setDropLongitude(BigDecimal.valueOf(77.6245));
        delivery.setDropContactName("Chef John");
        delivery.setDropContactPhone("+919876543211");
        delivery.setAttemptCount(0);
        return delivery;
    }

    private DeliveryQuote createQuote(Long deliveryId, String providerCode, VehicleType vehicleType) {
        DeliveryQuote quote = new DeliveryQuote();
        quote.setDeliveryId(deliveryId);
        quote.setDeliveryProviderId(1L);
        quote.setProviderCode(providerCode);
        quote.setVehicleType(vehicleType);
        quote.setProviderQuoteId("quote-123");
        quote.setAmount(new BigDecimal("150.00"));
        return quote;
    }

    @Test
    @DisplayName("Two-wheeler delivery assigns a 3-minute assignment deadline")
    void book_twoWheeler_sets3MinuteAssignmentDeadline() {
        Delivery delivery = createDelivery(1L, VehicleType.TWO_WHEELER);
        DeliveryQuote quote = createQuote(1L, "PORTER", VehicleType.TWO_WHEELER);

        when(quoting.usableQuotes(1L, List.of())).thenReturn(List.of(quote));
        when(registry.adapter("PORTER")).thenReturn(providerAdapter);
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "porter-order-1",
                new BigDecimal("150.00"),
                "INR",
                25,
                Instant.now().plusSeconds(1500),
                "https://track.porter.in/1"
        ));

        Instant beforeBooking = Instant.now();
        boolean booked = service.book(delivery, List.of(), "INITIAL");
        Instant afterBooking = Instant.now();

        assertThat(booked).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PROVIDER_SELECTED);
        assertThat(delivery.getAssignmentDeadline())
                .isAfterOrEqualTo(beforeBooking.plus(Duration.ofMinutes(3)))
                .isBeforeOrEqualTo(afterBooking.plus(Duration.ofMinutes(3)).plusSeconds(1));

        ArgumentCaptor<DeliveryBookedEvent> eventCaptor = ArgumentCaptor.forClass(DeliveryBookedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().deliveryId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().assignmentDeadline()).isEqualTo(delivery.getAssignmentDeadline());
    }

    @Test
    @DisplayName("Three-wheeler delivery assigns a 12-minute assignment deadline")
    void book_threeWheeler_sets12MinuteAssignmentDeadline() {
        Delivery delivery = createDelivery(2L, VehicleType.THREE_WHEELER);
        DeliveryQuote quote = createQuote(2L, "PORTER", VehicleType.THREE_WHEELER);

        when(quoting.usableQuotes(2L, List.of())).thenReturn(List.of(quote));
        when(registry.adapter("PORTER")).thenReturn(providerAdapter);
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "porter-order-2",
                new BigDecimal("350.00"),
                "INR",
                45,
                Instant.now().plusSeconds(2700),
                "https://track.porter.in/2"
        ));

        Instant beforeBooking = Instant.now();
        boolean booked = service.book(delivery, List.of(), "INITIAL");
        Instant afterBooking = Instant.now();

        assertThat(booked).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PROVIDER_SELECTED);
        assertThat(delivery.getAssignmentDeadline())
                .isAfterOrEqualTo(beforeBooking.plus(Duration.ofMinutes(12)))
                .isBeforeOrEqualTo(afterBooking.plus(Duration.ofMinutes(12)).plusSeconds(1));
    }

    @Test
    @DisplayName("Truck delivery assigns a 12-minute assignment deadline")
    void book_truck_sets12MinuteAssignmentDeadline() {
        Delivery delivery = createDelivery(3L, VehicleType.FOUR_WHEELER_TRUCK);
        DeliveryQuote quote = createQuote(3L, "PORTER", VehicleType.FOUR_WHEELER_TRUCK);

        when(quoting.usableQuotes(3L, List.of())).thenReturn(List.of(quote));
        when(registry.adapter("PORTER")).thenReturn(providerAdapter);
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "porter-order-3",
                new BigDecimal("650.00"),
                "INR",
                60,
                Instant.now().plusSeconds(3600),
                "https://track.porter.in/3"
        ));

        Instant beforeBooking = Instant.now();
        boolean booked = service.book(delivery, List.of(), "INITIAL");
        Instant afterBooking = Instant.now();

        assertThat(booked).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PROVIDER_SELECTED);
        assertThat(delivery.getAssignmentDeadline())
                .isAfterOrEqualTo(beforeBooking.plus(Duration.ofMinutes(12)))
                .isBeforeOrEqualTo(afterBooking.plus(Duration.ofMinutes(12)).plusSeconds(1));
    }

    @Test
    @DisplayName("Custom configured assignment timeouts are honored")
    void book_withCustomAssignmentTimeouts() {
        ReflectionTestUtils.setField(service, "bikeAssignmentTimeout", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(service, "truckAssignmentTimeout", Duration.ofMinutes(20));

        Delivery delivery = createDelivery(4L, VehicleType.FOUR_WHEELER_TRUCK);
        DeliveryQuote quote = createQuote(4L, "PORTER", VehicleType.FOUR_WHEELER_TRUCK);

        when(quoting.usableQuotes(4L, List.of())).thenReturn(List.of(quote));
        when(registry.adapter("PORTER")).thenReturn(providerAdapter);
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "porter-order-4",
                new BigDecimal("700.00"),
                "INR",
                60,
                Instant.now().plusSeconds(3600),
                "https://track.porter.in/4"
        ));

        Instant beforeBooking = Instant.now();
        boolean booked = service.book(delivery, List.of(), "INITIAL");
        Instant afterBooking = Instant.now();

        assertThat(booked).isTrue();
        assertThat(delivery.getAssignmentDeadline())
                .isAfterOrEqualTo(beforeBooking.plus(Duration.ofMinutes(20)))
                .isBeforeOrEqualTo(afterBooking.plus(Duration.ofMinutes(20)).plusSeconds(1));
    }
}
