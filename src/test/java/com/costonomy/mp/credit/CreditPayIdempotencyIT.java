package com.costonomy.mp.credit;

import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestOrder;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

/**
 * One captured payment, applied once (D-199, rule 4): whatever path brings it (the restaurant's confirm call, the
 * webhook, the poller), in any order, and at the same moment. Each test asserts the rows: one repayment and one payout
 * per supplier line, one credit payment per invoice, and the lines' figures still consistent.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayIdempotencyIT extends AbstractIntegrationTest {

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

    /** V = the restaurant's confirm, W = the webhook, P = the poller. */
    private void arrive(char path, CreditPaySupport.Setup setup, CreditPaySupport.Paid paid) throws Exception {
        switch (path) {
            case 'V' -> {
                var reply = p.verify(setup.buyer(), paid.attemptId(), paid.paymentId());
                assertThat(reply.status()).describedAs("verify " + reply.body()).isEqualTo(200);
            }
            case 'W' -> assertThat(p.capturedWebhook(paid.orderId(), paid.paymentId())).isEqualTo(200);
            case 'P' -> {
                p.ageAttempt(paid.attemptId(), 6);
                p.poll();
            }
            default -> throw new IllegalArgumentException("" + path);
        }
    }

    private void assertOnce(CreditPaySupport.Setup setup, CreditPaySupport.Paid paid, String describe) {
        assertThat(p.attemptStatus(paid.attemptId())).describedAs(describe).isEqualTo("PAID");
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 3);
        for (var inv : setup.invoices()) {
            assertThat(p.invoiceStatus(inv)).describedAs(describe).isEqualTo("PAID");
        }
        assertThat((BigDecimal) p.n.inv(setup.inv(0).id()).get("paid_amount")).describedAs(describe)
                .isEqualByComparingTo("6500.00");
        assertThat(p.repayments(paid.attemptId())).describedAs(describe).isEqualTo(2);
        assertThat(p.agreementEvents("CreditRepaymentReceived", setup.line(0).agreementId())).describedAs(describe)
                .isEqualTo(1);
        assertThat(p.refundRows(paid.attemptId())).describedAs(describe).isEmpty();
        assertThat(provider.refundedOf(paid.paymentId())).describedAs(describe + ": no refund of a payment that paid")
                .isEqualByComparingTo("0");
        assertThat(p.auditsFor("CREDIT_PAY_ATTEMPT_PAID", paid.attemptId())).describedAs(describe).isEqualTo(1);
    }

    @Test
    @DisplayName("the same capture through confirm, webhook and poll, in all six orders: one repayment per line, one payout per line")
    void allSixOrders() throws Exception {
        for (String order : List.of("VWP", "VPW", "WVP", "WPV", "PVW", "PWV")) {
            var setup = p.standard();
            var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
            for (char path : order.toCharArray()) {
                arrive(path, setup, paid);
            }
            assertOnce(setup, paid, order);
        }
    }

    @Test
    @DisplayName("confirm, webhook and poll at the same moment, five times: still one apply each time")
    void allThreeAtOnce() throws Exception {
        for (int run = 1; run <= 5; run++) {
            var setup = p.standard();
            var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
            p.ageAttempt(paid.attemptId(), 6);
            List<Callable<Object>> calls = List.of(
                    () -> p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()),
                    () -> p.capturedWebhook(paid.orderId(), paid.paymentId()),
                    () -> {
                        p.poll();
                        return null;
                    });

            var results = p.concurrently(calls);

            var verify = (CreditWalletSupport.Reply) results.get(0);
            assertThat(verify.status()).describedAs("run " + run + " " + verify.body()).isIn(200, 409);
            assertOnce(setup, paid, "run " + run);
        }
    }

    @Test
    @DisplayName("several confirm calls and webhooks of one payment at once: one apply")
    void manyOfTheSamePathAtOnce() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        var calls = new ArrayList<Callable<Object>>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()));
            calls.add(() -> p.capturedWebhook(paid.orderId(), paid.paymentId()));
        }

        p.concurrently(calls);

        assertOnce(setup, paid, "six at once");
    }

    @Test
    @DisplayName("the apply waits for the attempt row: while another transaction holds it and marks the payment applied, the apply reads that and does nothing")
    void applyWaitsForTheAttemptRow() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var holder = jdbc.getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("select id from credit_payment_attempt where id = ? for update")) {
                lock.setLong(1, paid.attemptId());
                lock.executeQuery();
            }
            // What a concurrent apply that has not committed yet would have written to the attempt row.
            try (var mark = holder.prepareStatement(
                    "update credit_payment_attempt set status = 'PAID', razorpay_payment_id = ?, paid_at = now(6) where id = ?")) {
                mark.setString(1, paid.paymentId());
                mark.setLong(2, paid.attemptId());
                mark.executeUpdate();
            }

            var waiting = pool.submit(() -> p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()));
            Thread.sleep(2500);
            assertThat(waiting.isDone()).describedAs("the apply must wait for the attempt row").isFalse();

            holder.commit();
            var reply = waiting.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        // The apply saw the attempt already applied with this payment, so it did not apply it a second time.
        assertThat(p.repayments(paid.attemptId())).isZero();
        assertThat(p.refundRows(paid.attemptId())).isEmpty();
        for (var inv : setup.invoices()) {
            assertThat(p.invoiceStatus(inv)).isNotEqualTo("PAID");
        }
    }

    @Test
    @DisplayName("a webhook redelivered with the same event id is a duplicate: accepted, ignored, nothing applied twice")
    void webhookRedelivery() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        String event = "evt_" + UUID.randomUUID();

        var first = p.webhookReply("payment.captured", paid.orderId(), paid.paymentId(), event);
        var second = p.webhookReply("payment.captured", paid.orderId(), paid.paymentId(), event);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.data().get("processed").asBoolean()).isTrue();
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.data().get("processed").asBoolean()).isFalse();
        assertThat(p.count("select count(*) from payment_webhook_event where provider_event_id = ?", event))
                .isEqualTo(1);
        assertOnce(setup, paid, "redelivered");
    }

    @Test
    @DisplayName("two different event ids for the same payment: one apply; the event is PROCESSED and not tied to an order payment")
    void twoEventsOnePayment() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        String one = "evt_" + UUID.randomUUID();
        String two = "evt_" + UUID.randomUUID();

        assertThat(p.webhook("payment.captured", paid.orderId(), paid.paymentId(), one)).isEqualTo(200);
        assertThat(p.webhook("order.paid", paid.orderId(), paid.paymentId(), two)).isEqualTo(200);

        assertOnce(setup, paid, "two events");
        for (String id : List.of(one, two)) {
            var row = jdbc.queryForMap("select status, payment_id from payment_webhook_event where provider_event_id = ?", id);
            assertThat(row.get("status")).isEqualTo("PROCESSED");
            assertThat(row.get("payment_id")).describedAs("not an order payment").isNull();
        }
    }

    @Test
    @DisplayName("payment.authorized and payment.failed for a credit order change nothing and are not errors")
    void otherEventsAreHarmless() throws Exception {
        var setup = p.standard();
        long id = p.createOk(setup.buyer(), p.allItems(setup), "UPI");
        String order = p.orderOf(id);
        var declined = provider.declineAttempt(order);
        var before = p.snapshot(setup);

        assertThat(p.webhook("payment.failed", order, declined.providerPaymentId(), "evt_" + UUID.randomUUID()))
                .isEqualTo(200);

        assertThat(p.snapshot(setup)).isEqualTo(before);
        assertThat(p.attemptStatus(id)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select status from payment_webhook_event where provider_payment_id = ?",
                String.class, declined.providerPaymentId())).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("a second captured payment on the same order after PAID is refunded in full (DUPLICATE_CAPTURE); invoices are unchanged")
    void secondCaptureIsRefunded() throws Exception {
        var setup = p.standard();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);
        var invoicesBefore = p.s.snapshot(setup.line(0)).invoices();
        String second = provider.completeCheckout(paid.orderId(), "upi").providerPaymentId();

        assertThat(p.capturedWebhook(paid.orderId(), second)).isEqualTo(200);

        var refunds = p.refundRows(paid.attemptId());
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).get("reason")).isEqualTo("DUPLICATE_CAPTURE");
        assertThat(refunds.get(0).get("razorpay_payment_id")).isEqualTo(second);
        assertThat(refunds.get(0).get("destination")).isEqualTo("ORIGINAL");
        assertThat(refunds.get(0).get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) refunds.get(0).get("amount")).isEqualByComparingTo("12480.00");
        assertThat(p.auditsFor("CREDIT_PAY_DUPLICATE_CAPTURE", paid.attemptId())).isEqualTo(1);
        // The attempt still belongs to the first payment and nothing was applied again.
        assertThat(p.attempt(paid.attemptId()).get("razorpay_payment_id")).isEqualTo(paid.paymentId());
        assertThat(p.s.snapshot(setup.line(0)).invoices()).isEqualTo(invoicesBefore);
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 3);

        // The same duplicate arriving again, by webhook or by confirm, is still one refund row.
        assertThat(p.capturedWebhook(paid.orderId(), second)).isEqualTo(200);
        var again = p.verify(setup.buyer(), paid.attemptId(), second);
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);
        assertThat(p.refundRows(paid.attemptId())).hasSize(1);

        // And the money goes back to the card it came from.
        p.refunds();
        assertThat(provider.refundedOf(second)).isEqualByComparingTo("12480.00");
        assertThat(provider.refundedOf(paid.paymentId())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a webhook for an order we do not know is IGNORED, as before")
    void unknownOrderIgnored() throws Exception {
        String event = "evt_" + UUID.randomUUID();

        assertThat(p.webhook("payment.captured", "order_nobody_knows", "pay_nobody_knows", event)).isEqualTo(200);

        assertThat(jdbc.queryForObject("select status from payment_webhook_event where provider_event_id = ?",
                String.class, event)).isEqualTo("IGNORED");
    }

    @Test
    @DisplayName("an order payment's webhook still reaches the order payment, beside the credit listener")
    void orderPaymentWebhookStillWorks() throws Exception {
        var buyer = p.s.newBuyer();
        var seller = p.s.newSeller();
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = p.s.api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
        var placed = new TestOrder(mvc, json, p.s.api).place(buyer.token(), buyer.outletId(), seller.token(), skuId, 10);
        var payment = provider.completeCheckout(placed.providerOrderId());

        int status = p.webhook("payment.authorized", placed.providerOrderId(), payment.providerPaymentId(),
                "evt_" + UUID.randomUUID());

        assertThat(status).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class,
                placed.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select payment_id from payment_webhook_event where provider_payment_id = ?",
                Long.class, payment.providerPaymentId())).isEqualTo(placed.paymentId());
    }
}
