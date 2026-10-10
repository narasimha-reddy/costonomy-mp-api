package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryGatingTest {

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
    private final PidgeProperties pidgeProperties = new PidgeProperties();

    private DeliveryService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryService(
                deliveries, events, locations, attempts, quoting, booking,
                eventService, orderBridge, timeline, directory, registry,
                accessControl, auditService, pidgeProperties);
    }

    @Test
    @DisplayName("autoDispatch cleanly skips orders when deliveryMode is PICKUP")
    void autoDispatchSkipsPickup() {
        var order = new DeliveryDirectory.OrderInfo(
                100L, "MP-100", "READY_FOR_PICKUP",
                10L, 20L, BigDecimal.ZERO, "PICKUP",
                BigDecimal.valueOf(5), null);

        when(directory.order(100L)).thenReturn(order);
        when(deliveries.findBySupplierOrderId(100L)).thenReturn(Optional.empty());

        var result = service.autoDispatch(100L);

        assertThat(result).isNull();
        verifyNoInteractions(quoting);
        verifyNoInteractions(booking);
        verify(deliveries, never()).save(any());
    }

    @Test
    @DisplayName("autoDispatch assigns driver for SUPPLIER_DELIVERY without external courier quoting")
    void autoDispatchSupplierOwnLogistics() {
        var order = new DeliveryDirectory.OrderInfo(
                101L, "MP-101", "READY_FOR_PICKUP",
                10L, 20L, BigDecimal.ZERO, "SUPPLIER_DELIVERY",
                BigDecimal.valueOf(5), null);

        when(directory.order(101L)).thenReturn(order);
        when(deliveries.findBySupplierOrderId(101L)).thenReturn(Optional.empty());
        when(directory.pickupFor(20L)).thenReturn(new DeliveryDirectory.Place(
                "Store", "Address 1", BigDecimal.ONE, BigDecimal.ONE, "Driver Joe", "+919999999999"));
        when(directory.dropFor(10L)).thenReturn(new DeliveryDirectory.Place(
                "Outlet", "Address 2", BigDecimal.TEN, BigDecimal.TEN, "Manager", "+918888888888"));

        var policy = new DeliveryDirectory.DeliveryPolicy(true, false, BigDecimal.ZERO, null, BigDecimal.ZERO, null, null);
        when(directory.deliveryPolicy(20L)).thenReturn(policy);

        var result = service.autoDispatch(101L);

        assertThat(result).isNotNull();
        assertThat(result.mode()).isEqualTo(DeliveryMode.SUPPLIER_OWN);
        assertThat(result.status()).isEqualTo(DeliveryStatus.DRIVER_ASSIGNED);
        assertThat(result.driverName()).isEqualTo("Driver Joe");

        verifyNoInteractions(quoting);
        verifyNoInteractions(booking);
    }
}
