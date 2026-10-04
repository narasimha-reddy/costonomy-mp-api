package com.costonomy.mp.procurement.subscription;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.credit.service.CreditAgreementService;
import com.costonomy.mp.credit.service.CreditLedgerService;
import com.costonomy.mp.delivery.slot.DeliverySlot;
import com.costonomy.mp.delivery.slot.DeliverySlotRepository;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.repository.OrderNumberGenerator;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.wallet.service.WalletService;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptions;

    @Mock
    private SubscriptionSkipDateRepository skipDates;

    @Mock
    private SupplierOrderRepository supplierOrders;

    @Mock
    private SupplierOrderItemRepository supplierOrderItems;

    @Mock
    private SupplierOfferRepository supplierOffers;

    @Mock
    private DeliverySlotRepository deliverySlots;

    @Mock
    private OrderNumberGenerator orderNumbers;

    @Mock
    private AccessControlService accessControl;

    @Mock
    private AuditService auditService;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private WalletService walletService;

    @Mock
    private CreditAgreementService creditAgreements;

    @Mock
    private CreditLedgerService creditLedger;

    @Mock
    private com.costonomy.mp.common.outbox.OutboxService outbox;

    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(
                subscriptions, skipDates, supplierOrders, supplierOrderItems,
                supplierOffers, deliverySlots, orderNumbers, accessControl, auditService, outbox, jdbc,
                walletService, creditAgreements, creditLedger);
    }

    @Test
    @DisplayName("Create subscription successfully creates active recurring replenishment")
    void createSubscriptionSuccess() {
        LocalDate tomorrow = LocalDate.now(SubscriptionService.ZONE).plusDays(1);

        var req = new SubscriptionDtos.CreateSubscriptionRequest(
                10L, 20L, new BigDecimal("10.00"), "LTR",
                SubscriptionFrequency.DAILY, 5L, "SUPPLIER_DELIVERY", "WALLET",
                tomorrow, null, "Fresh Milk daily delivery"
        );

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(20L), eq(10L)))
                .thenReturn(100L); // canonicalProductId

        when(subscriptions.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            s.setId(501L);
            return s;
        });

        when(jdbc.queryForObject(eq("select name from outlet where id = ?"), eq(String.class), anyLong()))
                .thenReturn("Downtown Cafe");
        when(jdbc.queryForObject(eq("select name from supplier_store where id = ?"), eq(String.class), anyLong()))
                .thenReturn("Fresh Dairy Store");

        var res = service.createSubscription(1L, 2L, req);

        verify(accessControl).requireScoped(1L, Permissions.PROCUREMENT_CREATE, ScopeType.OUTLET, 2L, "Outlet");
        assertThat(res.id()).isEqualTo(501L);
        assertThat(res.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(res.nextDeliveryDate()).isEqualTo(tomorrow);
        assertThat(res.quantity()).isEqualByComparingTo("10.00");
        assertThat(res.paymentMethod()).isEqualTo("WALLET");
    }

    @Test
    @DisplayName("Create subscription rejects past start date")
    void createSubscriptionPastDate() {
        LocalDate yesterday = LocalDate.now(SubscriptionService.ZONE).minusDays(1);

        var req = new SubscriptionDtos.CreateSubscriptionRequest(
                10L, 20L, new BigDecimal("10.00"), "LTR",
                SubscriptionFrequency.DAILY, 5L, "SUPPLIER_DELIVERY", "WALLET",
                yesterday, null, "Fresh Milk"
        );

        assertThatThrownBy(() -> service.createSubscription(1L, 2L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Subscription start date cannot be in the past");
    }

    @Test
    @DisplayName("Pause and Resume updates subscription status")
    void pauseAndResumeSubscription() {
        Subscription sub = new Subscription();
        sub.setId(501L);
        sub.setOutletId(2L);
        sub.setSupplierStoreId(10L);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(LocalDate.now(SubscriptionService.ZONE));
        sub.setFrequency(SubscriptionFrequency.DAILY);
        sub.setPaymentMethod("WALLET");

        when(subscriptions.findById(501L)).thenReturn(Optional.of(sub));
        when(jdbc.queryForObject(eq("select name from outlet where id = ?"), eq(String.class), anyLong()))
                .thenReturn("Downtown Cafe");
        when(jdbc.queryForObject(eq("select name from supplier_store where id = ?"), eq(String.class), anyLong()))
                .thenReturn("Fresh Dairy Store");

        var paused = service.pauseSubscription(1L, 501L);
        assertThat(paused.status()).isEqualTo(SubscriptionStatus.PAUSED);

        var resumed = service.resumeSubscription(1L, 501L);
        assertThat(resumed.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("Generate daily orders gates confirmation on wallet funding")
    void generateDailyOrdersWalletFunded() {
        LocalDate today = LocalDate.now(SubscriptionService.ZONE);

        Subscription sub = new Subscription();
        sub.setId(501L);
        sub.setOutletId(2L);
        sub.setSupplierStoreId(10L);
        sub.setCanonicalProductId(100L);
        sub.setSupplierSkuId(20L);
        sub.setQuantity(new BigDecimal("5.00"));
        sub.setUnit("KG");
        sub.setFrequency(SubscriptionFrequency.DAILY);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(today.minusDays(5));
        sub.setPreferredSlotId(3L);
        sub.setDeliveryMode("SUPPLIER_DELIVERY");
        sub.setPaymentMethod("WALLET");

        when(subscriptions.findBySupplierStoreIdAndStatus(10L, SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(sub));
        when(skipDates.existsBySubscriptionIdAndSkipDate(501L, today)).thenReturn(false);

        // Mock no existing order for today
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(501L), any()))
                .thenReturn(0);

        SupplierOffer offer = new SupplierOffer();
        offer.setSellingPrice(new BigDecimal("120.00"));
        offer.setGstRate(new BigDecimal("5.00"));
        when(supplierOffers.findBySupplierSkuIdAndStatus(20L, "ACTIVE"))
                .thenReturn(Optional.of(offer));

        when(orderNumbers.next()).thenReturn("MP-261002-000100");

        when(supplierOrders.save(any(SupplierOrder.class))).thenAnswer(inv -> {
            SupplierOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(901L);
            }
            return o;
        });

        // Wallet debit succeeds
        when(walletService.debitFor(eq(2L), any(), any())).thenReturn(true);

        var res = service.generateDailyOrders(1L, 10L, today);

        verify(accessControl).requireScoped(1L, Permissions.STORE_EDIT, ScopeType.SUPPLIER_STORE, 10L, "SupplierStore");
        assertThat(res.ordersGenerated()).isEqualTo(1);
        assertThat(res.orderIds()).contains(901L);

        verify(supplierOrders, atLeastOnce()).save(argThat(o ->
                o.isSubscriptionOrder() &&
                o.getDeliverySlotId().equals(3L) &&
                o.getStatus() == SupplierOrderStatus.CONFIRMED &&
                "COMPLETED".equals(o.getPaymentStatus()) &&
                o.getScheduledDeliveryDate().equals(today)
        ));
    }

    @Test
    @DisplayName("Generate daily orders leaves order DRAFT if wallet insufficient balance")
    void generateDailyOrdersWalletUnfunded() {
        LocalDate today = LocalDate.now(SubscriptionService.ZONE);

        Subscription sub = new Subscription();
        sub.setId(502L);
        sub.setOutletId(2L);
        sub.setSupplierStoreId(10L);
        sub.setCanonicalProductId(100L);
        sub.setSupplierSkuId(20L);
        sub.setQuantity(new BigDecimal("5.00"));
        sub.setUnit("KG");
        sub.setFrequency(SubscriptionFrequency.DAILY);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(today.minusDays(5));
        sub.setPreferredSlotId(3L);
        sub.setDeliveryMode("SUPPLIER_DELIVERY");
        sub.setPaymentMethod("WALLET");

        when(subscriptions.findBySupplierStoreIdAndStatus(10L, SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(sub));
        when(skipDates.existsBySubscriptionIdAndSkipDate(502L, today)).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(502L), any())).thenReturn(0);

        SupplierOffer offer = new SupplierOffer();
        offer.setSellingPrice(new BigDecimal("120.00"));
        offer.setGstRate(new BigDecimal("5.00"));
        when(supplierOffers.findBySupplierSkuIdAndStatus(20L, "ACTIVE")).thenReturn(Optional.of(offer));
        when(orderNumbers.next()).thenReturn("MP-261002-000101");

        when(supplierOrders.save(any(SupplierOrder.class))).thenAnswer(inv -> {
            SupplierOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(902L);
            }
            return o;
        });

        // Wallet debit fails (insufficient funds)
        when(walletService.debitFor(eq(2L), any(), any())).thenReturn(false);

        var res = service.generateDailyOrders(1L, 10L, today);

        // Not confirmed!
        assertThat(res.ordersGenerated()).isEqualTo(0);
        assertThat(res.orderIds()).isEmpty();
    }
}
