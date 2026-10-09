package com.costonomy.mp.credit;

import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.invoice.LogCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Money that arrives while nobody is looking (D-196): the restaurant closed the app after paying, the webhook was lost,
 * the payment came after the attempt expired, or the attempt never reached Razorpay. The poller and the expiry rules,
 * driven by calling the job directly and ageing rows in SQL.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayReconcileIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;

    @BeforeEach
    void setUp() {
        reset(provider);
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
    }

    /** Makes the poller's last look at the attempt {@code minutes} older. */
    private void ageCheck(long attemptId, int minutes) {
        jdbc.update("update credit_payment_attempt set checked_at = date_sub(checked_at, interval ? minute) "
                + "where id = ?", minutes, attemptId);
    }

    @Test
    @DisplayName("the app was closed after paying: no confirm, six minutes on, the poll applies it and the restaurant is told")
    void pollAppliesAClosedApp() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        p.ageAttempt(paid.attemptId(), 6);

        p.poll();

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("PAID");
        assertThat(p.attempt(paid.attemptId()).get("razorpay_payment_id")).isEqualTo(paid.paymentId());
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 3);
        for (var inv : setup.invoices()) {
            assertThat(p.invoiceStatus(inv)).isEqualTo("PAID");
        }
        // Applied while nobody watched, so the restaurant is told; the supplier is told once per supplier.
        assertThat(p.events("CreditPaymentCompleted", paid.attemptId())).isEqualTo(1);
        assertThat(p.events("CreditRepaymentReceived", paid.attemptId())).isEqualTo(2);
        assertThat(p.auditsFor("CREDIT_PAY_ATTEMPT_PAID", paid.attemptId())).isEqualTo(1);
        var payload = json.readTree(jdbc.queryForObject("select payload from outbox_event "
                + "where event_type = 'CreditPaymentCompleted' and json_extract(payload, '$.attemptId') = ?",
                String.class, paid.attemptId()));
        assertThat(payload.get("invoiceCount").asInt()).isEqualTo(3);
        assertThat(payload.get("invoiceCountText").asText()).isEqualTo("3 invoices");
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("12480.00");
        p.relayEvents("CreditPaymentCompleted", paid.attemptId());
        var told = jdbc.queryForList("select title, body, audience from notification "
                + "where event_type = 'CreditPaymentCompleted' and body like '%12,480.00%'");
        assertThat(told).hasSize(1);
        assertThat(told.get(0).get("audience")).isEqualTo("OUTLET");
        assertThat((String) told.get(0).get("title")).isEqualTo("Payment successful");
        assertThat((String) told.get(0).get("body")).isEqualTo("You paid ₹12,480.00 for 3 invoices. Those invoices are settled.");
    }

    @Test
    @DisplayName("an attempt younger than five minutes is left to the restaurant's own confirm and the webhook")
    void tooYoungToPoll() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        p.ageAttempt(paid.attemptId(), 4);

        p.poll();

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("CREATED");
        assertThat(p.repayments(paid.attemptId())).isZero();
        verify(provider, times(0)).findPaymentForOrder(paid.orderId());
    }

    @Test
    @DisplayName("looking and finding nothing marks the attempt checked; it is not asked again within the minute")
    void checkedIsNotAskedAgain() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        String order = p.orderOf(id);
        p.ageAttempt(id, 6);

        p.poll();
        p.poll();

        assertThat(p.attemptStatus(id)).isEqualTo("CREATED");
        assertThat(p.attempt(id).get("checked_at")).isNotNull();
        verify(provider, times(1)).findPaymentForOrder(order);

        ageCheck(id, 2);
        p.poll();
        verify(provider, times(2)).findPaymentForOrder(order);
    }

    @Test
    @DisplayName("a provider error during the poll is not an answer: the attempt is not marked checked, and the next poll applies it")
    void providerErrorIsNotChecked() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        p.ageAttempt(paid.attemptId(), 6);
        doThrow(new PaymentProviderException("Razorpay is down", true, "GATEWAY_ERROR"))
                .when(provider).findPaymentForOrder(eq(paid.orderId()));

        p.poll();

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("CREATED");
        assertThat(p.attempt(paid.attemptId()).get("checked_at")).isNull();
        assertThat(p.repayments(paid.attemptId())).isZero();

        reset(provider);
        p.poll();

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("PAID");
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 3);
    }

    @Test
    @DisplayName("not paid and 31 minutes old: EXPIRED; at 10 minutes it is still waiting")
    void unpaidExpires() throws Exception {
        var setup = p.standard();
        long young = p.createOk(setup.buyer(), p.items(setup.inv(0)), "UPI");
        long old = p.createOk(setup.buyer(), p.items(setup.inv(1)), "UPI");
        p.ageAttempt(young, 10);
        p.ageAttempt(old, 31);

        p.poll();

        assertThat(p.attemptStatus(young)).isEqualTo("CREATED");
        assertThat(p.attemptStatus(old)).isEqualTo("EXPIRED");
        var row = p.attempt(old);
        assertThat(row.get("failure_reason")).isEqualTo("Not paid within 30 minutes");
        assertThat(row.get("expired_at")).isNotNull();
        assertThat(row.get("open_selection_key")).describedAs("the selection can be started again").isNull();
        assertThat(p.repayments(old)).isZero();
        // The same selection can be started afresh.
        var again = p.create(setup.buyer(), p.items(setup.inv(1)), "UPI");
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(201);
        assertThat(again.data().get("attemptId").asLong()).isNotEqualTo(old);
    }

    @Test
    @DisplayName("a payment that turns up after the attempt expired is applied: by webhook")
    void latePaymentByWebhook() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        String order = p.orderOf(id);
        p.ageAttempt(id, 31);
        p.poll();
        assertThat(p.attemptStatus(id)).isEqualTo("EXPIRED");
        String paymentId = p.pay(id, "upi");

        assertThat(p.capturedWebhook(order, paymentId)).isEqualTo(200);

        assertThat(p.attemptStatus(id)).isEqualTo("PAID");
        p.assertAppliedOnce(setup, id, 2, 3);
        assertThat(p.refundRows(id)).isEmpty();
        assertThat(p.events("CreditPaymentCompleted", id)).isEqualTo(1);
        assertThat(p.attempt(id).get("open_selection_key")).isNull();
    }

    @Test
    @DisplayName("a payment that turns up after expiry is applied: by the restaurant's confirm, and by the poll when the webhook was lost")
    void latePaymentByConfirmAndPoll() throws Exception {
        var setup = p.standard();
        long byConfirm = p.createOk(setup.buyer(), p.items(setup.inv(0)), "UPI");
        long byPoll = p.createOk(setup.buyer(), p.items(setup.inv(2)), "UPI");
        p.ageAttempt(byConfirm, 31);
        p.ageAttempt(byPoll, 31);
        p.poll();
        assertThat(p.attemptStatus(byConfirm)).isEqualTo("EXPIRED");
        assertThat(p.attemptStatus(byPoll)).isEqualTo("EXPIRED");
        String late1 = p.pay(byConfirm, "upi");
        p.pay(byPoll, "upi");

        var reply = p.verify(setup.buyer(), byConfirm, late1);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("status").asText()).isEqualTo("PAID");

        // The poll looks again at an expired attempt that was last checked half an hour ago.
        ageCheck(byPoll, 31);
        p.poll();
        assertThat(p.attemptStatus(byConfirm)).isEqualTo("PAID");
        assertThat(p.attemptStatus(byPoll)).isEqualTo("PAID");
        assertThat(p.repayments(byConfirm)).isEqualTo(1);
        assertThat(p.repayments(byPoll)).isEqualTo(1);
        assertThat(p.invoiceStatus(setup.inv(0))).isEqualTo("PAID");
        assertThat(p.invoiceStatus(setup.inv(2))).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a late payment for an invoice that changed meanwhile is skipped and refunded like any other")
    void latePaymentWithChangedInvoice() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.items(setup.inv(0), setup.inv(2)), "UPI");
        p.ageAttempt(id, 31);
        p.poll();
        assertThat(p.supplierReceipt(setup.inv(2), "1980.00").status()).isEqualTo(200);
        String paymentId = p.pay(id, "upi");

        assertThat(p.capturedWebhook(p.orderOf(id), paymentId)).isEqualTo(200);

        assertThat(p.attemptStatus(id)).isEqualTo("PAID");
        assertThat(p.attemptInvoice(id, setup.inv(2)).get("skip_reason")).isEqualTo("SETTLED");
        assertThat(p.refundRows(id)).hasSize(1);
        assertThat(p.invoiceStatus(setup.inv(0))).isEqualTo("PAID");
    }

    @Test
    @DisplayName("an expired attempt older than 24 hours is no longer polled")
    void expiredForTooLongIsNotPolled() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        String order = p.orderOf(id);
        jdbc.update("update credit_payment_attempt set status = 'EXPIRED', open_selection_key = null, "
                + "failure_reason = 'Not paid within 30 minutes', expired_at = utc_timestamp(6), "
                + "created_at = date_sub(created_at, interval 25 hour) where id = ?", id);
        p.pay(id, "upi");

        p.poll();

        assertThat(p.attemptStatus(id)).isEqualTo("EXPIRED");
        assertThat(p.repayments(id)).isZero();
        verify(provider, times(0)).findPaymentForOrder(order);
    }

    @Test
    @DisplayName("an attempt that never reached Razorpay (no order) is FAILED 'Never reached Razorpay' after five minutes, not before")
    void neverOpened() throws Exception {
        var setup = p.standard();
        long young = p.createOk(setup.buyer(), p.items(setup.inv(0)), "UPI");
        long old = p.createOk(setup.buyer(), p.items(setup.inv(1)), "UPI");
        jdbc.update("update credit_payment_attempt set razorpay_order_id = null where id in (?, ?)", young, old);
        p.ageAttempt(young, 4);
        p.ageAttempt(old, 6);

        p.poll();

        assertThat(p.attemptStatus(young)).isEqualTo("CREATED");
        assertThat(p.attemptStatus(old)).isEqualTo("FAILED");
        assertThat(p.attempt(old).get("failure_reason")).isEqualTo("Never reached Razorpay");
        assertThat(p.attempt(old).get("open_selection_key")).isNull();
        assertThat(p.repayments(old)).isZero();
    }

    @Test
    @DisplayName("captured but the amount does not match: not applied, not refunded, one CREDIT_PAY_STUCK error after 15 minutes, alerted once")
    void stuckAlertOnce() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        // The order was opened for the original amount; now the attempt claims one rupee more.
        jdbc.update("update credit_payment_attempt set amount = amount + 1, total_charged = total_charged + 1 "
                + "where id = ?", id);
        p.pay(id, "upi");
        var before = p.snapshot(setup);

        try (var logs = new LogCapture()) {
            // At six minutes it is only young: refused quietly.
            p.ageAttempt(id, 6);
            p.poll();
            assertThat(p.attempt(id).get("stuck_alerted_at")).isNull();
            assertThat(logs.lines().stream().filter(l -> l.contains("CREDIT_PAY_STUCK")).count()).isZero();

            // At sixteen it needs a person.
            p.ageAttempt(id, 10);
            ageCheck(id, 2);
            p.poll();
            assertThat(logs.lines().stream().filter(l -> l.contains("CREDIT_PAY_STUCK")).count()).isEqualTo(1);
            assertThat(p.attempt(id).get("stuck_alerted_at")).isNotNull();

            // Not again.
            ageCheck(id, 2);
            p.poll();
            p.poll();
            assertThat(logs.lines().stream().filter(l -> l.contains("CREDIT_PAY_STUCK")).count()).isEqualTo(1);
        }

        assertThat(p.attemptStatus(id)).isEqualTo("CREATED");
        assertThat(p.snapshot(setup).lines()).describedAs("nothing applied").isEqualTo(before.lines());
        assertThat(p.snapshot(setup).providerRefunds()).describedAs("nothing refunded").isEqualTo(before.providerRefunds());
        assertThat(p.repayments(id)).isZero();
        assertThat(p.refundRows(id)).isEmpty();
    }

    @Test
    @DisplayName("a payment of the wrong amount is refused on confirm too: 400, nothing applied")
    void confirmWithWrongAmount() throws Exception {
        var setup = p.single("1000.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        jdbc.update("update credit_payment_attempt set amount = 1001, total_charged = 1001 where id = ?", id);
        String paymentId = p.pay(id, "upi");

        var reply = p.verify(setup.buyer(), id, paymentId);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
        assertThat(reply.body().at("/error/message").asText())
                .isEqualTo("That payment doesn't match this payment request. Nothing was paid.");
        assertThat(p.repayments(id)).isZero();
        assertThat(p.refundRows(id)).isEmpty();
        assertThat(p.invoiceStatus(setup.inv(0))).isNotEqualTo("PAID");
        verify(provider, times(0)).refund(any(), any(), any(), any());
    }
}
