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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A booking whose answer was lost, found again later by the sweep (D-204 audit, gap 11): adopted in every state where the
 * delivery still wanted a courier, with the deadline for its vehicle, and recorded exactly as a normal booking is (ledger,
 * booked event, timeline, audit). Never adopted where the delivery finished, was taken over, or already has a courier.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryBookingRecoveryTest {

    @Mock private DeliveryRepository deliveries;
    @Mock private DeliveryProviderAttemptRepository attempts;
    @Mock private DeliveryQuotingService quoting;
    @Mock private DeliveryProviderRegistry registry;
    @Mock private DeliveryTimeline timeline;
    @Mock private AuditService auditService;
    @Mock private DeliveryLedgerRepository ledger;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DeliveryDirectory directory;
    @Mock private DeliveryProvider adapter;
    @Mock private DeliveryRecoveryService recoveryService;
    @Mock private org.springframework.transaction.PlatformTransactionManager txManager;

    private DeliveryBookingService service;
    private Delivery delivery;
    private DeliveryProviderAttempt attempt;

    @BeforeEach
    void setUp() {
        lenient().when(txManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new DeliveryBookingService(deliveries, attempts, quoting, registry, timeline, auditService, ledger,
                eventPublisher, directory, recoveryService, txManager);
        lenient().when(attempts.save(any(DeliveryProviderAttempt.class))).thenAnswer(i -> i.getArgument(0));

        delivery = new Delivery();
        delivery.setId(7L);
        delivery.setMode(DeliveryMode.COSTONOMY);
        delivery.setVehicleType(VehicleType.TWO_WHEELER);
        delivery.setAttemptCount(2);
        lenient().when(deliveries.lockById(7L)).thenReturn(Optional.of(delivery));
        lenient().when(deliveries.findById(7L)).thenReturn(Optional.of(delivery));

        attempt = new DeliveryProviderAttempt();
        org.springframework.test.util.ReflectionTestUtils.setField(attempt, "id", 11L);
        attempt.setDeliveryId(7L);
        attempt.setProviderCode("PIDGE");
        attempt.setAttemptNumber(2);
        attempt.setOutcome("PENDING");
        attempt.setQuotedAmount(new BigDecimal("88.00"));
        attempt.setDeliveryProviderId(3L);
        attempt.setStartedAt(Instant.now().minus(Duration.ofMinutes(10)));
        lenient().when(attempts.findById(11L)).thenReturn(Optional.of(attempt));

        lenient().when(registry.adapter("PIDGE")).thenReturn(adapter);
        lenient().when(adapter.supportsLookupByReference()).thenReturn(true);
        lenient().when(adapter.findByReference(anyString(), anyString())).thenReturn(Optional.of(
                new DeliveryProvider.ProviderDelivery("pidge-found-9", DeliveryProvider.ProviderDeliveryStatus.PENDING,
                        null, null, null, 25, null, null, null, List.of())));
    }

    @ParameterizedTest(name = "adopted while {0}")
    @EnumSource(value = DeliveryStatus.class, names = {"DELIVERY_REQUESTED", "QUOTE_RECEIVED", "PROVIDER_UNAVAILABLE",
            "QUOTE_FAILED", "DRIVER_CANCELLED", "PICKUP_FAILED"})
    @DisplayName("a booking found after a lost answer is adopted wherever the delivery still wanted a courier and held none")
    void adoptedInEveryWantingState(DeliveryStatus state) {
        delivery.setStatus(state);

        service.recoverStalePendingAttempt(11L);

        assertThat(attempt.getOutcome()).isEqualTo("BOOKED");
        assertThat(attempt.getProviderDeliveryId()).isEqualTo("pidge-found-9");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PROVIDER_SELECTED);
        assertThat(delivery.getProviderDeliveryId()).isEqualTo("pidge-found-9");
        assertThat(delivery.getProviderCode()).isEqualTo("PIDGE");
        assertThat(delivery.getFee()).isEqualByComparingTo("88.00");
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        verify(adapter, never()).cancel(anyString(), anyString());
    }

    @Test
    @DisplayName("it is recorded as a normal booking is: ledger entry, booked event, timeline and audit")
    void recordedLikeANormalBooking() {
        delivery.setStatus(DeliveryStatus.QUOTE_RECEIVED);

        service.recoverStalePendingAttempt(11L);

        var entry = ArgumentCaptor.forClass(DeliveryLedgerEntry.class);
        verify(ledger).save(entry.capture());
        assertThat(entry.getValue().getEntryType()).isEqualTo("BOOKED");
        assertThat(entry.getValue().getAmount()).isEqualByComparingTo("88.00");
        assertThat(entry.getValue().getProviderDeliveryId()).isEqualTo("pidge-found-9");
        verify(eventPublisher).publishEvent(any(DeliveryBookedEvent.class));
        verify(timeline).record(eq(delivery), anyString(), eq(DeliveryStatus.PROVIDER_SELECTED), anyString());
        verify(auditService).record(isNull(), isNull(), eq("DELIVERY_BOOKED"), eq("DELIVERY"), eq(7L), isNull(),
                eq("PROVIDER_SELECTED"), anyString(), eq("SYSTEM"));
    }

    @Test
    @DisplayName("the assignment deadline is the vehicle's, not always the bike's")
    void usesTheVehiclesDeadline() {
        delivery.setStatus(DeliveryStatus.QUOTE_RECEIVED);
        delivery.setVehicleType(VehicleType.FOUR_WHEELER_TRUCK);

        service.recoverStalePendingAttempt(11L);

        assertThat(Duration.between(Instant.now(), delivery.getAssignmentDeadline()).toMinutes())
                .as("a truck gets its longer time to find a driver").isGreaterThanOrEqualTo(11);
    }

    @ParameterizedTest(name = "not adopted when {0}")
    @EnumSource(value = DeliveryStatus.class, names = {"DELIVERED", "CANCELLED", "PICKED_UP", "IN_TRANSIT"})
    @DisplayName("it is not adopted once the delivery finished or the goods are with a courier; the stray courier is stood down")
    void notAdoptedWhenNotWanted(DeliveryStatus state) {
        delivery.setStatus(state);

        service.recoverStalePendingAttempt(11L);

        assertThat(attempt.getOutcome()).isEqualTo("CANCELLED");
        assertThat(attempt.getEndedCause()).isEqualTo("RECOVERED_BUT_NOT_WANTED");
        assertThat(delivery.getProviderDeliveryId()).isNull();
        verify(adapter).cancel(eq("pidge-found-9"), anyString());
        verifyNoInteractions(ledger, eventPublisher);
    }

    @Test
    @DisplayName("it is not adopted when the supplier took the delivery over, or another courier already holds it")
    void notAdoptedWhenTakenOverOrHeld() {
        delivery.setStatus(DeliveryStatus.PROVIDER_SELECTED);
        delivery.setProviderDeliveryId("other-courier");

        service.recoverStalePendingAttempt(11L);

        assertThat(attempt.getOutcome()).isEqualTo("CANCELLED");
        assertThat(delivery.getProviderDeliveryId()).isEqualTo("other-courier");
        verify(adapter).cancel(eq("pidge-found-9"), anyString());

        // supplier took over
        attempt.setOutcome("PENDING");
        delivery.setProviderDeliveryId(null);
        delivery.setMode(DeliveryMode.SUPPLIER_OWN);
        delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
        service.recoverStalePendingAttempt(11L);
        assertThat(attempt.getOutcome()).isEqualTo("CANCELLED");
        assertThat(delivery.getProviderDeliveryId()).isNull();
    }
}
