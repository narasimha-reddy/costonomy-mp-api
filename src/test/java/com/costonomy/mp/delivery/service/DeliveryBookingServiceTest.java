package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.*;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.shadowfax.ShadowfaxContractException;
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
    private DeliveryDirectory directory;

    @Mock
    private DeliveryProvider providerAdapter;

    @Mock
    private DeliveryRecoveryService recoveryService;

    @Mock
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private DeliveryBookingService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(txManager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new DeliveryBookingService(
                deliveries,
                attempts,
                quoting,
                registry,
                timeline,
                auditService,
                ledger,
                eventPublisher,
                directory,
                recoveryService,
                txManager
        );
        lenient().when(attempts.save(any(DeliveryProviderAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Delivery createDelivery(Long id, VehicleType vehicleType) {
        Delivery delivery = new Delivery();
        delivery.setId(id);
        // book() reads the delivery afresh inside each of its own transactions.
        lenient().when(deliveries.lockById(id)).thenReturn(java.util.Optional.of(delivery));
        lenient().when(deliveries.findById(id)).thenReturn(java.util.Optional.of(delivery));
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

        when(quoting.usableQuotes(1L, List.of(), false)).thenReturn(List.of(quote));
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

        when(quoting.usableQuotes(2L, List.of(), false)).thenReturn(List.of(quote));
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

        when(quoting.usableQuotes(3L, List.of(), false)).thenReturn(List.of(quote));
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

        when(quoting.usableQuotes(4L, List.of(), false)).thenReturn(List.of(quote));
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

    @Test
    @DisplayName("Booking passes locality and goods value from our records to the carrier")
    void book_passesLocalitiesAndGoodsValueToProvider() {
        Delivery delivery = createDelivery(7L, VehicleType.TWO_WHEELER);
        delivery.setSupplierStoreId(31L);
        delivery.setOutletId(41L);
        DeliveryQuote quote = createQuote(7L, "MOCK_EXPRESS", VehicleType.TWO_WHEELER);
        var pickup = new DeliveryProvider.Locality("Secunderabad", "Telangana", "500003");
        var drop = new DeliveryProvider.Locality("Hyderabad", "Telangana", "500081");

        when(directory.pickupLocality(31L)).thenReturn(pickup);
        when(directory.dropLocality(41L)).thenReturn(drop);
        when(directory.goodsValue(200L)).thenReturn(new BigDecimal("1834.50"));
        when(quoting.usableQuotes(7L, List.of(), false)).thenReturn(List.of(quote));
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(providerAdapter);
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "m-1", new BigDecimal("80.00"), "INR", 25, Instant.now().plusSeconds(1500), null));

        assertThat(service.book(delivery, List.of(), "INITIAL")).isTrue();

        ArgumentCaptor<DeliveryProvider.BookingRequest> captor =
                ArgumentCaptor.forClass(DeliveryProvider.BookingRequest.class);
        verify(providerAdapter).book(captor.capture());
        assertThat(captor.getValue().pickupLocality()).isEqualTo(pickup);
        assertThat(captor.getValue().dropLocality()).isEqualTo(drop);
        assertThat(captor.getValue().goodsValue()).isEqualByComparingTo("1834.50");
    }

    @Test
    @DisplayName("A carrier that refuses for want of a fare fails over, and only the winner is charged")
    void book_whenFirstCarrierRefusesWithoutFare_failsOverAndChargesOnlyTheWinner() {
        Delivery delivery = createDelivery(8L, VehicleType.TWO_WHEELER);
        DeliveryQuote refusing = createQuote(8L, "SHADOWFAX", VehicleType.TWO_WHEELER);
        refusing.setAmount(new BigDecimal("50.00"));
        DeliveryQuote winner = createQuote(8L, "MOCK_EXPRESS", VehicleType.TWO_WHEELER);
        winner.setAmount(new BigDecimal("80.00"));
        DeliveryProvider shadowfax = mock(DeliveryProvider.class);

        when(quoting.usableQuotes(8L, List.of(), false)).thenReturn(List.of(refusing, winner));
        when(registry.adapter("SHADOWFAX")).thenReturn(shadowfax);
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(providerAdapter);
        when(shadowfax.book(any())).thenThrow(new ShadowfaxContractException(
                "Shadowfax create-order returns no fare; refusing to book"));
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "m-2", new BigDecimal("80.00"), "INR", 25, Instant.now().plusSeconds(1500), null));

        assertThat(service.book(delivery, List.of(), "INITIAL")).isTrue();

        assertThat(delivery.getProviderCode()).isEqualTo("MOCK_EXPRESS");
        assertThat(delivery.getFee()).isEqualByComparingTo("80.00");

        ArgumentCaptor<DeliveryProviderAttempt> attemptCaptor = ArgumentCaptor.forClass(DeliveryProviderAttempt.class);
        verify(attempts, atLeastOnce()).save(attemptCaptor.capture());
        var failed = attemptCaptor.getAllValues().stream()
                .filter(a -> "SHADOWFAX".equals(a.getProviderCode())).reduce((first, last) -> last).orElseThrow();
        assertThat(failed.getOutcome()).isEqualTo("FAILED");
        assertThat(failed.getFailureReason()).contains("fare");

        ArgumentCaptor<DeliveryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(DeliveryLedgerEntry.class);
        verify(ledger, times(1)).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getProviderCode()).isEqualTo("MOCK_EXPRESS");
        assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Each fallback attempt is sent with its own reference, and the timeout lookup uses the one that was sent")
    void book_fallbackAttemptsUseDistinctReferences() {
        Delivery delivery = createDelivery(12L, VehicleType.TWO_WHEELER);
        DeliveryQuote first = createQuote(12L, "SHADOWFAX", VehicleType.TWO_WHEELER);
        DeliveryQuote second = createQuote(12L, "MOCK_EXPRESS", VehicleType.TWO_WHEELER);
        DeliveryProvider shadowfax = mock(DeliveryProvider.class);
        when(quoting.usableQuotes(12L, List.of(), false)).thenReturn(List.of(first, second));
        when(registry.adapter("SHADOWFAX")).thenReturn(shadowfax);
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(providerAdapter);
        when(shadowfax.book(any())).thenThrow(new com.costonomy.mp.delivery.provider.DeliveryProviderException("SHADOWFAX", "refused", false));
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "m-9", new BigDecimal("80.00"), "INR", 25, Instant.now().plusSeconds(1500), null));

        assertThat(service.book(delivery, List.of(), "INITIAL")).isTrue();

        ArgumentCaptor<DeliveryProvider.BookingRequest> sent = ArgumentCaptor.forClass(DeliveryProvider.BookingRequest.class);
        verify(shadowfax).book(sent.capture());
        verify(providerAdapter).book(sent.capture());
        assertThat(sent.getAllValues()).extracting(DeliveryProvider.BookingRequest::idempotencyKey)
                .containsExactly("mp-delivery-12-1", "mp-delivery-12-2");
    }

    @Test
    @DisplayName("The attempt number comes from the locked delivery, not the caller's stale copy")
    void book_numbersTheAttemptFromTheLockedState() {
        Delivery delivery = createDelivery(13L, VehicleType.TWO_WHEELER);
        DeliveryQuote quote = createQuote(13L, "MOCK_EXPRESS", VehicleType.TWO_WHEELER);
        when(quoting.usableQuotes(13L, List.of(), false)).thenReturn(List.of(quote));
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(providerAdapter);
        when(providerAdapter.book(any())).thenReturn(new DeliveryProvider.Booking(
                "m-10", new BigDecimal("80.00"), "INR", 25, Instant.now().plusSeconds(1500), null));
        // The caller's copy says no attempts yet, but another booking has since used numbers 1 to 3.
        var latest = new DeliveryProviderAttempt();
        latest.setAttemptNumber(3);
        when(attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(13L)).thenReturn(java.util.Optional.of(latest));

        assertThat(service.book(delivery, List.of(), "INITIAL")).isTrue();

        ArgumentCaptor<DeliveryProvider.BookingRequest> sent = ArgumentCaptor.forClass(DeliveryProvider.BookingRequest.class);
        verify(providerAdapter).book(sent.capture());
        assertThat(sent.getValue().idempotencyKey()).isEqualTo("mp-delivery-13-4");
    }

    @Test
    @DisplayName("Losing the race for the attempt number to another booking is a clean no, not an error")
    void book_whenTheAttemptNumberIsTaken_standsAside() {
        Delivery delivery = createDelivery(14L, VehicleType.TWO_WHEELER);
        DeliveryQuote quote = createQuote(14L, "MOCK_EXPRESS", VehicleType.TWO_WHEELER);
        when(quoting.usableQuotes(14L, List.of(), false)).thenReturn(List.of(quote));
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(providerAdapter);
        when(attempts.save(any(DeliveryProviderAttempt.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_attempt_number"));

        assertThat(service.book(delivery, List.of(), "INITIAL")).isFalse();

        verify(providerAdapter, never()).book(any());
    }

    @Test
    @DisplayName("A courier booked after the delivery was cancelled is stood down, and the cancelled delivery stays cancelled")
    void book_whenTheDeliveryIsCancelledDuringTheCall_standsTheCourierDown() {
        Delivery delivery = createDelivery(15L, VehicleType.TWO_WHEELER);
        DeliveryQuote quote = createQuote(15L, "MOCK_EXPRESS", VehicleType.TWO_WHEELER);
        when(quoting.usableQuotes(15L, List.of(), false)).thenReturn(List.of(quote));
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(providerAdapter);
        // The provider call takes no lock, so the delivery is cancelled while it is in flight.
        when(providerAdapter.book(any())).thenAnswer(inv -> {
            delivery.setStatus(DeliveryStatus.CANCELLED);
            return new DeliveryProvider.Booking("m-77", new BigDecimal("80.00"), "INR", 25,
                    Instant.now().plusSeconds(1500), null);
        });

        assertThat(service.book(delivery, List.of(), "INITIAL")).isFalse();

        verify(providerAdapter).cancel(eq("m-77"), anyString());
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(delivery.getProviderDeliveryId()).isNull();
        verifyNoInteractions(ledger, eventPublisher);
    }

    @Test
    @DisplayName("A courier that cannot be stood down is retried durably, not forgotten")
    void book_whenTheUnwantedCourierCannotBeCancelled_schedulesTheCancellation() {
        Delivery delivery = createDelivery(16L, VehicleType.TWO_WHEELER);
        DeliveryQuote quote = createQuote(16L, "MOCK_EXPRESS", VehicleType.TWO_WHEELER);
        when(quoting.usableQuotes(16L, List.of(), false)).thenReturn(List.of(quote));
        when(registry.adapter("MOCK_EXPRESS")).thenReturn(providerAdapter);
        when(providerAdapter.book(any())).thenAnswer(inv -> {
            delivery.setStatus(DeliveryStatus.CANCELLED);
            return new DeliveryProvider.Booking("m-78", new BigDecimal("80.00"), "INR", 25,
                    Instant.now().plusSeconds(1500), null);
        });
        doThrow(new com.costonomy.mp.delivery.provider.DeliveryProviderException("MOCK_EXPRESS", "down", true))
                .when(providerAdapter).cancel(anyString(), anyString());

        assertThat(service.book(delivery, List.of(), "INITIAL")).isFalse();

        verify(recoveryService).scheduleCancellation(eq("SUPPLIER"), eq(16L), eq("MOCK_EXPRESS"), eq("m-78"), anyString());
    }

    @Test
    @DisplayName("When every carrier fails, a delivery cancelled meanwhile is not turned back into 'no partner'")
    void book_whenAllCarriersFailAfterTheDeliveryWasCancelled_leavesItCancelled() {
        Delivery delivery = createDelivery(17L, VehicleType.TWO_WHEELER);
        DeliveryQuote quote = createQuote(17L, "SHADOWFAX", VehicleType.TWO_WHEELER);
        DeliveryProvider shadowfax = mock(DeliveryProvider.class);
        when(quoting.usableQuotes(17L, List.of(), false)).thenReturn(List.of(quote));
        when(registry.adapter("SHADOWFAX")).thenReturn(shadowfax);
        when(shadowfax.book(any())).thenAnswer(inv -> {
            delivery.setStatus(DeliveryStatus.CANCELLED);
            throw new com.costonomy.mp.delivery.provider.DeliveryProviderException("SHADOWFAX", "refused", false);
        });

        assertThat(service.book(delivery, List.of(), "INITIAL")).isFalse();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(delivery.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("A delivery a courier already holds is not booked a second time, even if it was free when the caller looked")
    void book_whenACourierAlreadyHoldsTheDelivery_booksNothing() {
        Delivery delivery = createDelivery(11L, VehicleType.TWO_WHEELER);
        DeliveryQuote quote = createQuote(11L, "SHADOWFAX", VehicleType.TWO_WHEELER);
        DeliveryProvider shadowfax = mock(DeliveryProvider.class);
        when(quoting.usableQuotes(11L, List.of(), false)).thenReturn(List.of(quote));
        when(registry.adapter("SHADOWFAX")).thenReturn(shadowfax);
        // Another booking finished between the caller's check and this reservation.
        delivery.setProviderDeliveryId("sfx-already");

        assertThat(service.book(delivery, List.of(), "INITIAL")).isFalse();

        verify(shadowfax, never()).book(any());
        verify(attempts, never()).save(any(DeliveryProviderAttempt.class));
        assertThat(delivery.getProviderDeliveryId()).isEqualTo("sfx-already");
    }

    @Test
    @DisplayName("When every carrier refuses, the delivery is marked unavailable and nothing is booked or charged")
    void book_whenEveryCarrierRefuses_marksUnavailableAndBooksNothing() {
        Delivery delivery = createDelivery(9L, VehicleType.TWO_WHEELER);
        DeliveryQuote refusing = createQuote(9L, "SHADOWFAX", VehicleType.TWO_WHEELER);
        DeliveryProvider shadowfax = mock(DeliveryProvider.class);

        when(quoting.usableQuotes(9L, List.of(), false)).thenReturn(List.of(refusing));
        when(registry.adapter("SHADOWFAX")).thenReturn(shadowfax);
        when(shadowfax.book(any())).thenThrow(new ShadowfaxContractException("no fare"));

        assertThat(service.book(delivery, List.of(), "INITIAL")).isFalse();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PROVIDER_UNAVAILABLE);
        assertThat(delivery.getFailureCode()).isEqualTo("ALL_PROVIDERS_FAILED");
        assertThat(delivery.getProviderCode()).isNull();
        verifyNoInteractions(ledger, eventPublisher);
    }
}
