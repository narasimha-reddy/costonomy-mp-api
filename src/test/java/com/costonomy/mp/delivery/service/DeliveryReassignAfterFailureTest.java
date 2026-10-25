package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryQuote;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.delivery.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Booking the next courier after a provider let go. Every guard here protects against paying for a courier nobody
 * needs, or booking two for one delivery.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryReassignAfterFailureTest {

    @Mock private DeliveryRepository deliveries;
    @Mock private DeliveryEventRepository events;
    @Mock private DeliveryLocationRepository locations;
    @Mock private DeliveryProviderAttemptRepository attempts;
    @Mock private DeliveryQuotingService quoting;
    @Mock private DeliveryBookingService booking;
    @Mock private DeliveryEventService eventService;
    @Mock private DeliveryOrderBridge orderBridge;
    @Mock private DeliveryTimeline timeline;
    @Mock private DeliveryDirectory directory;
    @Mock private DeliveryProviderRegistry registry;
    @Mock private AccessControlService accessControl;
    @Mock private AuditService auditService;
    @Mock private DeliveryProvider pidge;
    @Mock private DeliveryRecoveryService recoveryService;
    @Mock private OrderCancellationPort orderCancellationPort;
    @Mock private SupplierCourierPayableSync payableSync;
    @Mock private SupplierDeliveryMovementService movementService;

    private DeliveryService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryService(
                deliveries, events, locations, attempts, quoting, booking,
                eventService, orderBridge, timeline, directory, registry,
                accessControl, auditService, new PidgeProperties(), recoveryService,
                orderCancellationPort, payableSync, movementService,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    private static Delivery delivery(DeliveryStatus status) {
        var d = new Delivery();
        d.setId(7L);
        d.setSupplierOrderId(100L);
        d.setMode(DeliveryMode.COSTONOMY);
        d.setStatus(status);
        d.setProviderCode("PIDGE");
        d.setProviderDeliveryId("pidge-1");
        return d;
    }

    private static DeliveryDirectory.OrderInfo order(String status) {
        return new DeliveryDirectory.OrderInfo(100L, "MP-100", status, 10L, 20L, BigDecimal.ZERO,
                "COSTONOMY_DELIVERY", BigDecimal.valueOf(5), null);
    }

    private void lock(Delivery d) {
        org.mockito.Mockito.lenient().when(deliveries.lockById(7L)).thenReturn(Optional.of(d));
        // The booking step and the closing transaction each read it again.
        org.mockito.Mockito.lenient().when(deliveries.findById(7L)).thenReturn(Optional.of(d));
    }

    @Test
    @DisplayName("books another courier, skipping the ones already tried, and stands the old consignment down")
    void booksTheNextCourier() {
        var d = delivery(DeliveryStatus.DRIVER_CANCELLED);
        lock(d);
        when(directory.order(100L)).thenReturn(order("READY_FOR_PICKUP"));
        when(booking.triedProviders(7L)).thenReturn(List.of("PIDGE"));
        when(quoting.usableQuotes(eq(7L), eq(List.of("PIDGE")), anyBoolean())).thenReturn(List.of(new DeliveryQuote()));
        when(registry.adapter("PIDGE")).thenReturn(pidge);
        doAnswer(inv -> { d.setStatus(DeliveryStatus.PROVIDER_SELECTED); return true; })
                .when(booking).book(eq(d), eq(List.of("PIDGE")), eq("REASSIGNMENT"));

        assertThat(service.reassignAfterProviderFailure(7L, "pidge-1", "Rider cancelled")).isTrue();

        verify(pidge).cancel("pidge-1", "Rider cancelled");
        verify(booking).book(d, List.of("PIDGE"), "REASSIGNMENT");
    }

    @Test
    @DisplayName("the released courier's id is cleared from the delivery, so a late event from it finds nothing")
    void releasedConsignmentIdIsCleared() {
        var d = delivery(DeliveryStatus.DRIVER_CANCELLED);
        lock(d);
        when(directory.order(100L)).thenReturn(order("READY_FOR_PICKUP"));
        when(booking.triedProviders(7L)).thenReturn(List.of("PIDGE"));
        when(quoting.usableQuotes(eq(7L), eq(List.of("PIDGE")), anyBoolean())).thenReturn(List.of(new DeliveryQuote()));
        when(registry.adapter("PIDGE")).thenReturn(pidge);
        var idWhenBooking = new java.util.concurrent.atomic.AtomicReference<String>("unset");
        doAnswer(inv -> { idWhenBooking.set(d.getProviderDeliveryId()); return true; })
                .when(booking).book(eq(d), anyList(), eq("REASSIGNMENT"));

        service.reassignAfterProviderFailure(7L, "pidge-1", "Rider cancelled");

        assertThat(idWhenBooking.get()).as("already cleared when the replacement is being booked").isNull();
        verify(pidge).cancel("pidge-1", "Rider cancelled");
    }

    @Test
    @DisplayName("re-quotes when no quote is left to use")
    void requotesWhenNoQuotesLeft() {
        var d = delivery(DeliveryStatus.PICKUP_FAILED);
        lock(d);
        when(directory.order(100L)).thenReturn(order("READY_FOR_PICKUP"));
        when(booking.triedProviders(7L)).thenReturn(List.of("PIDGE"));
        when(quoting.usableQuotes(eq(7L), anyList(), anyBoolean())).thenReturn(List.of());
        when(quoting.gather(eq(d), any(), any(), any(), eq(List.of("PIDGE"))))
                .thenReturn(new DeliveryQuotingService.Outcome(null, List.of(), false));

        assertThat(service.reassignAfterProviderFailure(7L, null, "x")).isFalse();

        assertThat(d.getStatus()).as("nobody else covers it").isEqualTo(DeliveryStatus.QUOTE_FAILED);
        assertThat(d.getNoPartnerSince()).as("the no-partner retry takes over").isNotNull();
    }

    @Test
    @DisplayName("does nothing if the delivery has already moved to a different consignment")
    void staleEventDoesNothing() {
        var d = delivery(DeliveryStatus.DRIVER_CANCELLED);
        d.setProviderDeliveryId("pidge-2");
        lock(d);

        assertThat(service.reassignAfterProviderFailure(7L, "pidge-1", "x")).isFalse();

        verifyNoInteractions(booking, quoting);
    }

    @Test
    @DisplayName("does nothing unless the delivery is still in a stand-down status")
    void onlyFromStoodDownStatuses() {
        for (var status : new DeliveryStatus[]{DeliveryStatus.PROVIDER_SELECTED, DeliveryStatus.DRIVER_ASSIGNED,
                DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED}) {
            lock(delivery(status));
            assertThat(service.reassignAfterProviderFailure(7L, null, "x")).as(status.name()).isFalse();
        }
        verifyNoInteractions(booking, quoting);
    }

    @Test
    @DisplayName("does not book a courier for an order that is no longer waiting for pickup")
    void orderNoLongerReady() {
        for (var status : new String[]{"CANCELLED", "OUT_FOR_DELIVERY", "DELIVERED"}) {
            lock(delivery(DeliveryStatus.DRIVER_CANCELLED));
            when(directory.order(100L)).thenReturn(order(status));
            assertThat(service.reassignAfterProviderFailure(7L, null, "x")).as(status).isFalse();
        }
        when(directory.order(100L)).thenReturn(null);
        lock(delivery(DeliveryStatus.DRIVER_CANCELLED));
        assertThat(service.reassignAfterProviderFailure(7L, null, "x")).isFalse();
        verifyNoInteractions(booking, quoting);
    }

    @Test
    @DisplayName("supplier's own delivery is never given to a courier")
    void ownDeliveryIsLeftAlone() {
        var d = delivery(DeliveryStatus.DRIVER_CANCELLED);
        d.setMode(DeliveryMode.SUPPLIER_OWN);
        lock(d);

        assertThat(service.reassignAfterProviderFailure(7L, null, "x")).isFalse();
        verifyNoInteractions(booking, quoting);
    }

    @Test
    @DisplayName("a missing delivery is a no-op")
    void missingDelivery() {
        when(deliveries.lockById(7L)).thenReturn(Optional.empty());
        assertThat(service.reassignAfterProviderFailure(7L, null, "x")).isFalse();
    }

    @Test
    @DisplayName("bookQuoted books a delivery that was quoted but never booked, skipping couriers already tried")
    void bookQuotedBooksAQuotedDelivery() {
        var d = delivery(DeliveryStatus.QUOTE_RECEIVED);
        d.setProviderDeliveryId(null); // quoted, so no courier holds it
        lock(d);
        when(booking.triedProviders(7L)).thenReturn(List.of("PIDGE"));
        doAnswer(inv -> { d.setStatus(DeliveryStatus.PROVIDER_SELECTED); return true; })
                .when(booking).book(eq(d), eq(List.of("PIDGE")), eq("BOOKING"));

        assertThat(service.bookQuoted(7L)).isTrue();
    }

    @Test
    @DisplayName("bookQuoted leaves a delivery alone when a booking is already in progress or it is past quoting")
    void bookQuotedLeavesOthersAlone() {
        var pending = delivery(DeliveryStatus.QUOTE_RECEIVED);
        lock(pending);
        when(attempts.existsByDeliveryIdAndOutcome(7L, "PENDING")).thenReturn(true);
        assertThat(service.bookQuoted(7L)).isFalse();

        var booked = delivery(DeliveryStatus.PROVIDER_SELECTED);
        lock(booked);
        org.mockito.Mockito.reset(attempts);
        assertThat(service.bookQuoted(7L)).isFalse();

        verify(booking, never()).book(any(), anyList(), anyString());
    }

    @Test
    @DisplayName("bookQuoted does not book another courier when an earlier attempt's outcome is unknown")
    void bookQuotedWaitsForReconciliation() {
        var d = delivery(DeliveryStatus.QUOTE_RECEIVED);
        d.setProviderDeliveryId(null);
        d.setReconciliationRequired(true); // an ambiguous attempt: the provider may have booked
        lock(d);

        assertThat(service.bookQuoted(7L)).isFalse();

        verify(booking, never()).book(any(), anyList(), anyString());
    }

    @Test
    @DisplayName("a stood-down delivery with a booking already pending is not booked a second time")
    void pendingAttemptBlocksSecondReassign() {
        var d = delivery(DeliveryStatus.DRIVER_CANCELLED);
        lock(d);
        when(directory.order(100L)).thenReturn(order("READY_FOR_PICKUP"));
        when(attempts.existsByDeliveryIdAndOutcome(7L, "PENDING")).thenReturn(true);

        assertThat(service.reassignAfterProviderFailure(7L, null, "x")).isFalse();

        verify(booking, never()).book(any(), anyList(), anyString());
    }

    @Test
    @DisplayName("a quote result that arrives after the supplier took the delivery over changes nothing and books nothing")
    void quoteResultAfterSwitchToOwnIsDiscarded() {
        var d = delivery(DeliveryStatus.QUOTE_FAILED);
        d.setProviderDeliveryId(null);
        d.setNoPartnerSince(java.time.Instant.now().minusSeconds(600));
        lock(d);
        when(directory.order(100L)).thenReturn(order("READY_FOR_PICKUP"));
        // While the quotes are being fetched with no lock held, the supplier switches the delivery to their own.
        when(quoting.gather(eq(d), any(), any(), any(), anyList())).thenAnswer(inv -> {
            d.setMode(DeliveryMode.SUPPLIER_OWN);
            d.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
            return new DeliveryQuotingService.Outcome(null, List.of(new DeliveryQuote()), true);
        });

        service.retryNoPartner(7L);

        assertThat(d.getMode()).isEqualTo(DeliveryMode.SUPPLIER_OWN);
        assertThat(d.getStatus()).as("not overwritten by the late quote").isEqualTo(DeliveryStatus.DRIVER_ASSIGNED);
        verify(booking, never()).book(any(), anyList(), anyString());
    }
}
