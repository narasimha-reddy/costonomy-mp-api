package com.costonomy.mp.procurement.subscription;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.service.DeliveryDirectory;
import com.costonomy.mp.delivery.slot.DeliverySlot;
import com.costonomy.mp.delivery.slot.DeliverySlotRepository;
import com.costonomy.mp.procurement.service.OrderFunding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subscriptions;
    @Mock private SubscriptionSkipDateRepository skipDates;
    @Mock private DeliverySlotRepository deliverySlots;
    @Mock private OrderFunding orderFunding;
    @Mock private DeliveryDirectory deliveryPolicies;
    @Mock private AccessControlService accessControl;
    @Mock private AuditService auditService;
    @Mock private JdbcTemplate jdbc;

    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(subscriptions, skipDates, deliverySlots, orderFunding,
                deliveryPolicies, accessControl, auditService, jdbc);
    }

    private SubscriptionDtos.CreateSubscriptionRequest request(String paymentMethod, String deliveryMode,
                                                               Long slotId, LocalDate start) {
        return new SubscriptionDtos.CreateSubscriptionRequest(
                10L, 20L, new BigDecimal("10.00"), "client-sent-unit",
                SubscriptionFrequency.DAILY, slotId, deliveryMode, paymentMethod,
                start, null, "Fresh Milk daily delivery");
    }

    @SuppressWarnings("unchecked")
    private void skuExists() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(20L), eq(10L)))
                .thenReturn(new Object[] {100L, "LTR"});
    }

    private void storeDelivers() {
        when(deliveryPolicies.deliveryPolicy(10L)).thenReturn(new DeliveryDirectory.DeliveryPolicy(
                true, true, BigDecimal.ZERO, null, BigDecimal.ZERO, null, null));
    }

    private void namesForResponse() {
        when(jdbc.queryForObject(eq("select name from outlet where id = ?"), eq(String.class), anyLong()))
                .thenReturn("Downtown Cafe");
        when(jdbc.queryForObject(eq("select name from supplier_store where id = ?"), eq(String.class), anyLong()))
                .thenReturn("Fresh Dairy Store");
    }

    private Subscription active() {
        Subscription sub = new Subscription();
        sub.setId(501L);
        sub.setOutletId(2L);
        sub.setSupplierStoreId(10L);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(LocalDate.now(SubscriptionService.ZONE));
        sub.setFrequency(SubscriptionFrequency.DAILY);
        sub.setPaymentMethod("WALLET");
        return sub;
    }

    @Test
    @DisplayName("Create takes the unit from the SKU and ignores the one the client sent")
    void createSuccess() {
        LocalDate tomorrow = LocalDate.now(SubscriptionService.ZONE).plusDays(1);
        skuExists();
        storeDelivers();
        when(orderFunding.canFund("WALLET", 2L, 10L)).thenReturn(true);
        when(subscriptions.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            s.setId(501L);
            return s;
        });
        namesForResponse();

        var res = service.createSubscription(1L, 2L, request("WALLET", "SUPPLIER_DELIVERY", null, tomorrow));

        verify(accessControl).requireScoped(1L, Permissions.PROCUREMENT_CREATE, ScopeType.OUTLET, 2L, "Outlet");
        assertThat(res.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(res.nextDeliveryDate()).isEqualTo(tomorrow);
        assertThat(res.unit()).isEqualTo("LTR");
        assertThat(res.paymentMethod()).isEqualTo("WALLET");
    }

    @Test
    @DisplayName("Create rejects a past start date")
    void createPastDate() {
        var req = request("WALLET", "SUPPLIER_DELIVERY", null, LocalDate.now(SubscriptionService.ZONE).minusDays(1));

        assertThatThrownBy(() -> service.createSubscription(1L, 2L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be in the past");
    }

    @Test
    @DisplayName("Create rejects a payment method that is not wallet or credit, and saves nothing")
    void createRejectsOtherPaymentMethods() {
        skuExists();
        var req = request("PREPAID", "SUPPLIER_DELIVERY", null, LocalDate.now(SubscriptionService.ZONE));

        assertThatThrownBy(() -> service.createSubscription(1L, 2L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("wallet or on credit");
        verify(subscriptions, never()).save(any());
    }

    @Test
    @DisplayName("Create rejects Costonomy delivery and unknown delivery modes instead of defaulting them")
    void createRejectsOtherDeliveryModes() {
        skuExists();
        when(orderFunding.canFund("WALLET", 2L, 10L)).thenReturn(true);

        for (String mode : new String[] {"COSTONOMY_DELIVERY", "TELEPORT"}) {
            var req = request("WALLET", mode, null, LocalDate.now(SubscriptionService.ZONE));
            assertThatThrownBy(() -> service.createSubscription(1L, 2L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("picked up");
        }
        verify(subscriptions, never()).save(any());
    }

    @Test
    @DisplayName("Create rejects credit without an active agreement with that supplier")
    void createRejectsCreditWithoutAgreement() {
        skuExists();
        when(orderFunding.canFund("CREDIT", 2L, 10L)).thenReturn(false);
        var req = request("credit", "PICKUP", null, LocalDate.now(SubscriptionService.ZONE));

        assertThatThrownBy(() -> service.createSubscription(1L, 2L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active credit");
        verify(subscriptions, never()).save(any());
    }

    @Test
    @DisplayName("Create rejects a slot that belongs to another store or is inactive")
    void createRejectsBadSlot() {
        skuExists();
        storeDelivers();
        when(orderFunding.canFund("WALLET", 2L, 10L)).thenReturn(true);
        var foreign = new DeliverySlot();
        foreign.setSupplierStoreId(99L);
        var inactive = new DeliverySlot();
        inactive.setSupplierStoreId(10L);
        inactive.setActive(false);
        when(deliverySlots.findById(5L)).thenReturn(Optional.of(foreign));
        when(deliverySlots.findById(6L)).thenReturn(Optional.of(inactive));

        for (long slotId : new long[] {5L, 6L, 7L}) {
            var req = request("WALLET", "SUPPLIER_DELIVERY", slotId, LocalDate.now(SubscriptionService.ZONE));
            assertThatThrownBy(() -> service.createSubscription(1L, 2L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("slot");
        }
        verify(subscriptions, never()).save(any());
    }

    @Test
    @DisplayName("Pause and resume need the right to create orders for the outlet")
    void pauseAndResumeNeedProcurementCreate() {
        Subscription sub = active();
        when(subscriptions.findById(501L)).thenReturn(Optional.of(sub));
        namesForResponse();

        assertThat(service.pauseSubscription(1L, 501L).status()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(service.resumeSubscription(1L, 501L).status()).isEqualTo(SubscriptionStatus.ACTIVE);

        verify(accessControl, org.mockito.Mockito.times(2))
                .requireScoped(1L, Permissions.PROCUREMENT_CREATE, ScopeType.OUTLET, 2L, "Outlet");
    }

    @Test
    @DisplayName("Someone who cannot create orders for the outlet cannot pause, resume, cancel or skip, and nothing changes")
    void mutationsRefusedWithoutProcurementCreate() {
        Subscription sub = active();
        when(subscriptions.findById(501L)).thenReturn(Optional.of(sub));
        doThrow(new NotFoundException("Outlet", 2L)).when(accessControl)
                .requireScoped(1L, Permissions.PROCUREMENT_CREATE, ScopeType.OUTLET, 2L, "Outlet");

        var skip = new SubscriptionDtos.AddSkipDateRequest(
                LocalDate.now(SubscriptionService.ZONE).plusDays(3), "away");
        assertThatThrownBy(() -> service.pauseSubscription(1L, 501L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.resumeSubscription(1L, 501L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancelSubscription(1L, 501L, "x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.addSkipDate(1L, 501L, skip)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.removeSkipDate(1L, 501L, skip.skipDate())).isInstanceOf(NotFoundException.class);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(subscriptions, never()).save(any());
        verify(skipDates, never()).save(any());
    }

    @Test
    @DisplayName("Create rejects supplier delivery from a store that does not deliver")
    void createRejectsSupplierDeliveryFromStoreThatDoesNotDeliver() {
        skuExists();
        when(orderFunding.canFund("WALLET", 2L, 10L)).thenReturn(true);
        when(deliveryPolicies.deliveryPolicy(10L)).thenReturn(DeliveryDirectory.DeliveryPolicy.DEFAULT);
        var req = request("WALLET", "SUPPLIER_DELIVERY", null, LocalDate.now(SubscriptionService.ZONE));

        assertThatThrownBy(() -> service.createSubscription(1L, 2L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("doesn't deliver");
        verify(subscriptions, never()).save(any());
    }
}
