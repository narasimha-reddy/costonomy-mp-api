package com.costonomy.mp.payment;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.PaymentWebhookEvent;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPayment;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.PaymentTransactionRepository;
import com.costonomy.mp.payment.service.PaymentService;
import com.costonomy.mp.payment.service.PaymentWebhookService;
import com.costonomy.mp.payment.service.PaymentWebhookStore;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.service.PaymentJobsAccess;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A provider payment funds only the payment whose intent it completed.
 *
 * <p>Before this, {@code confirm} fetched whatever payment id the client sent and
 * applied its state: any authorised payment — a cheaper one, another outlet's —
 * would release this order to its supplier. The assertions are on the payment's
 * state and on what was released, not only on the error.
 */
class PaymentOwnershipTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final AuditService audit = mock(AuditService.class);
    private PaymentService service;
    private Payment payment;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // Runs the callback inline: these tests are about what is written, and the
        // integration tests prove no transaction is open around the provider call.
        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
        doAnswer(call -> {
            ((Consumer<TransactionStatus>) call.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());

        service = new PaymentService(payments, mock(PaymentTransactionRepository.class),
                provider, audit, mock(OutboxService.class), tx,
                mock(com.costonomy.mp.payment.service.CancellationLedger.class),
                new com.costonomy.mp.payment.service.PaymentHoldPolicy(4320));

        payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", 7L);
        payment.setSupplierOrderId(70L);
        payment.setOutletId(1L);
        payment.setProvider("RAZORPAY");
        payment.setProviderOrderId("order_mine");
        payment.setAuthorizedAmount(new BigDecimal("1500.00"));
        payment.setStatus(PaymentStatus.CREATED);
        when(payments.findById(7L)).thenReturn(Optional.of(payment));
        // Every state change locks the row first (D-099).
        when(payments.lockById(7L)).thenReturn(Optional.of(payment));
    }

    @Test
    @DisplayName("confirming with another order's payment is refused and changes nothing")
    void foreignPaymentIsRefused() {
        when(provider.fetchPayment("pay_other")).thenReturn(authorized("pay_other", "order_other", "10.00"));

        assertThatThrownBy(() -> service.confirm(7L, "pay_other"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getProviderPaymentId()).isNull();
        verify(audit).record(any(), any(), eq("PAYMENT_CONFIRM_MISMATCH"), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("confirming with an id the provider does not know leaves the order payable")
    void unknownIdIsRefusedNotFailed() {
        when(provider.fetchPayment("pay_garbled")).thenThrow(
                new com.costonomy.mp.payment.provider.PaymentProviderException(
                        "Razorpay refused the lookup: 400", false, "400"));

        assertThatThrownBy(() -> service.confirm(7L, "pay_garbled"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        // FAILED is terminal: had this failed the payment, the customer could
        // never pay for the order, even with a real payment.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        when(provider.fetchPayment("pay_mine")).thenReturn(authorized("pay_mine", "order_mine", "1500.00"));
        assertThat(service.confirm(7L, "pay_mine").getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("confirming with this order's payment authorises it")
    void ownPaymentAuthorises() {
        when(provider.fetchPayment("pay_mine")).thenReturn(authorized("pay_mine", "order_mine", "1500.00"));

        var confirmed = service.confirm(7L, "pay_mine");

        assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(confirmed.getProviderPaymentId()).isEqualTo("pay_mine");
    }

    @Test
    @DisplayName("a payment against our intent for a different amount is not applied")
    void wrongAmountIsNotApplied() {
        // Cannot happen with Razorpay orders, whose amount is fixed — which is
        // exactly why, if it ever does, it must not fund the order.
        var applied = service.applyProviderState(payment,
                authorized("pay_mine", "order_mine", "15.00"), "WEBHOOK");

        assertThat(applied.getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    @DisplayName("a webhook's event id is read from Razorpay's header before the body")
    void webhookEventIdFromHeader() {
        var store = mock(PaymentWebhookStore.class);
        when(store.record(any())).thenAnswer(call -> call.getArgument(0));
        when(provider.verifySignature(any(), any())).thenReturn(true);
        when(provider.name()).thenReturn("RAZORPAY");

        var webhooks = new PaymentWebhookService(store, payments, service,
                mock(OrderReleaseService.class), provider, new ObjectMapper());

        // Razorpay's real shape: no id in the body at all.
        webhooks.handle("""
                {"entity":"event","event":"payment.failed","payload":{}}""",
                "sig", "evt_from_header");

        var recorded = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(store).record(recorded.capture());
        assertThat(recorded.getValue().getProviderEventId()).isEqualTo("evt_from_header");
    }

    @Test
    @DisplayName("a webhook about a payment we never created stays IGNORED")
    void unknownPaymentWebhookStaysIgnored() {
        var store = mock(PaymentWebhookStore.class);
        when(store.record(any())).thenAnswer(call -> call.getArgument(0));
        when(provider.verifySignature(any(), any())).thenReturn(true);
        when(provider.name()).thenReturn("RAZORPAY");
        when(payments.findByProviderPaymentId(any())).thenReturn(Optional.empty());
        when(payments.findByProviderOrderId(any())).thenReturn(Optional.empty());

        new PaymentWebhookService(store, payments, service, mock(OrderReleaseService.class),
                provider, new ObjectMapper()).handle("""
                {"entity":"event","event":"payment.authorized","payload":{"payment":{"entity":
                  {"id":"pay_not_ours","order_id":"order_not_ours"}}}}""", "sig", "evt_1");

        var finished = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(store).finish(finished.capture());
        assertThat(finished.getValue().getStatus()).isEqualTo("IGNORED");
        verify(provider, never()).fetchPayment(any());
    }

    @Test
    @DisplayName("a provider that cannot be reached leaves the payment exactly as it was")
    void unreachableProviderChangesNothing() {
        when(provider.fetchPayment("pay_mine")).thenThrow(
                PaymentProviderException.unreachable("timeout", new RuntimeException()));

        assertThatThrownBy(() -> service.confirm(7L, "pay_mine"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getProviderPaymentId()).isNull();
    }

    @Test
    @DisplayName("an unpaid intent is asked about less often as it ages")
    void intentLookupBacksOff() {
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        // Ten minutes old: every two minutes.
        Instant young = now.minus(Duration.ofMinutes(10));
        assertThat(PaymentJobsAccess.due(young, now.minusSeconds(130), now)).isTrue();
        assertThat(PaymentJobsAccess.due(young, now.minusSeconds(60), now)).isFalse();
        // Five hours old: every thirty minutes, the ceiling.
        Instant old = now.minus(Duration.ofHours(5));
        assertThat(PaymentJobsAccess.due(old, now.minus(Duration.ofMinutes(20)), now)).isFalse();
        assertThat(PaymentJobsAccess.due(old, now.minus(Duration.ofMinutes(31)), now)).isTrue();
        // Never asked: always due.
        assertThat(PaymentJobsAccess.due(old, null, now)).isTrue();
    }

    private static ProviderPayment authorized(String id, String orderId, String amount) {
        return new ProviderPayment(id, orderId, ProviderPaymentStatus.AUTHORIZED,
                new BigDecimal(amount), BigDecimal.ZERO, null, null);
    }
}
