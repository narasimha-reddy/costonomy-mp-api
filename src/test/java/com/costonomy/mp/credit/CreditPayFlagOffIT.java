package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditPaySupport.Setup;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider.AuthorizationRequest;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The switch (D-199): {@code online-pay.enabled=false} stops a payment from being <i>started</i> and nothing else.
 * Money already in flight at the moment of switching off still lands, whichever way it arrives, and its refunds are
 * still sent.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=false",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayFlagOffIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @Value("${costonomy.mp.credit.online-pay.enabled}") private boolean flag;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;

    @BeforeEach
    void setUp() {
        reset(provider);
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
    }

    /**
     * An attempt as it was before the switch went off: a Razorpay order from the spied provider (auto-capture) and the
     * attempt and invoice rows inserted by SQL, since the endpoint that makes them is now closed.
     */
    private long existingAttempt(Setup setup, CreditPaySupport.Inv... invoices) throws Exception {
        var items = p.items(invoices);
        BigDecimal total = BigDecimal.ZERO;
        for (var inv : invoices) {
            total = total.add(p.outstanding(inv.id()));
        }
        String key = UUID.randomUUID().toString();
        var intent = provider.createAuthorization(new AuthorizationRequest("creditpay-fixture-" + key, total, "INR",
                "Mandi credit payment", "creditpay-fixture-" + key, null, true));
        long userId = p.e.userId(setup.buyer().token());
        jdbc.update("""
                insert into credit_payment_attempt (outlet_id, created_by, method, channel, status, invoice_count, amount,
                    fee_percent, fee_amount, total_charged, currency, razorpay_order_id, idempotency_key, open_selection_key)
                values (?, ?, 'UPI', 'ONLINE', 'CREATED', ?, ?, 0, 0, ?, 'INR', ?, ?, ?)
                """, setup.buyer().outletId(), userId, invoices.length, total, total, intent.providerOrderId(),
                "outlet-" + setup.buyer().outletId() + "-" + key,
                "outlet-" + setup.buyer().outletId() + "-ONLINE-" + key);
        long id = jdbc.queryForObject("select id from credit_payment_attempt where razorpay_order_id = ?", Long.class,
                intent.providerOrderId());
        for (var inv : invoices) {
            jdbc.update("""
                    insert into credit_payment_attempt_invoice (attempt_id, credit_invoice_id, credit_agreement_id,
                        supplier_store_id, expected_amount, outcome)
                    values (?, ?, ?, ?, ?, 'PENDING')
                    """, id, inv.id(), inv.line().agreementId(), inv.line().seller().storeId(), p.outstanding(inv.id()));
        }
        assertThat(items).hasSize(invoices.length);
        return id;
    }

    @Test
    @DisplayName("the flag is off in this class")
    void flagIsOff() {
        assertThat(flag).isFalse();
    }

    @Test
    @DisplayName("picker, quote and create (UPI, card and wallet) are 403 FORBIDDEN 'Paying credit online isn't available yet.' and nothing is written or claimed")
    void startEndpointsAreClosed() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var items = p.allItems(setup);
        var before = p.snapshot(setup);
        long keysBefore = p.allCreateKeys();

        var replies = List.of(p.payable(setup.buyer()), p.quote(setup.buyer(), items),
                p.create(setup.buyer(), items, "UPI"), p.create(setup.buyer(), items, "CARD"),
                p.create(setup.buyer(), items, "WALLET"));

        for (var reply : replies) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(403);
            assertThat(reply.code()).isEqualTo("FORBIDDEN");
            assertThat(reply.body().at("/error/message").asText()).isEqualTo("Paying credit online isn't available yet.");
        }
        var after = p.snapshot(setup);
        assertThat(after).describedAs("not a row, a debit, a key or an idempotency record").isEqualTo(before);
        assertThat(p.allCreateKeys()).describedAs("flag off claims no key at all, not even a FAILED one").isEqualTo(keysBefore);
        verify(provider, times(0)).createAuthorization(any());
    }

    @Test
    @DisplayName("an attempt from before the switch-off is still applied by the restaurant's confirm, and it can be read")
    void verifyStillWorks() throws Exception {
        var setup = p.standard();
        long id = existingAttempt(setup, setup.inv(0), setup.inv(1), setup.inv(2));
        String paymentId = p.pay(id, "upi");
        assertThat(p.status(setup.buyer(), id).status()).describedAs("reads work with the flag off").isEqualTo(200);

        var reply = p.verify(setup.buyer(), id, paymentId);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("status").asText()).isEqualTo("PAID");
        p.assertAppliedOnce(setup, id, 2, 3);
        assertThat(p.status(setup.buyer(), id).data().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("an attempt from before the switch-off is still applied by the webhook")
    void webhookStillWorks() throws Exception {
        var setup = p.standard();
        long id = existingAttempt(setup, setup.inv(0), setup.inv(1), setup.inv(2));
        String paymentId = p.pay(id, "upi");

        assertThat(p.capturedWebhook(p.orderOf(id), paymentId)).isEqualTo(200);

        assertThat(p.attemptStatus(id)).isEqualTo("PAID");
        p.assertAppliedOnce(setup, id, 2, 3);
        assertThat(p.events("CreditPaymentCompleted", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("an attempt from before the switch-off is still applied by the poll")
    void pollStillWorks() throws Exception {
        var setup = p.standard();
        long id = existingAttempt(setup, setup.inv(0), setup.inv(1), setup.inv(2));
        p.pay(id, "upi");
        p.ageAttempt(id, 6);

        p.poll();

        assertThat(p.attemptStatus(id)).isEqualTo("PAID");
        p.assertAppliedOnce(setup, id, 2, 3);
    }

    @Test
    @DisplayName("an unpaid attempt from before the switch-off still expires")
    void expiryStillWorks() throws Exception {
        var setup = p.standard();
        long id = existingAttempt(setup, setup.inv(0));
        p.ageAttempt(id, 31);

        p.poll();

        assertThat(p.attemptStatus(id)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("refunds of a credit payment are still sent with the flag off")
    void refundsStillSent() throws Exception {
        var setup = p.standard();
        long id = existingAttempt(setup, setup.inv(0), setup.inv(1));
        String paymentId = p.pay(id, "upi");
        assertThat(p.creditNote(setup.inv(1), "500.00").status()).isEqualTo(201);
        assertThat(p.verify(setup.buyer(), id, paymentId).status()).isEqualTo(200);
        assertThat(p.refundRows(id)).hasSize(1);

        p.refunds();

        assertThat(provider.refundedOf(paymentId)).isEqualByComparingTo("4000.00");
        assertThat(p.refundRows(id).get(0).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("the old wallet repayment endpoint is not governed by this switch")
    void oldWalletEndpointUnaffected() throws Exception {
        var setup = p.single("1000.00");
        p.s.topUp(setup.buyer(), "5000.00");

        var reply = p.s.repay(setup.buyer().token(), setup.line(0).agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", List.of(setup.inv(0).id())));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
    }

    @Test
    @DisplayName("the summary tells the app the switch is off, so 'Paid direct' stays and 'Pay dues' is hidden")
    void summaryReportsOff() throws Exception {
        var setup = p.single("1000.00");

        var reply = p.e.call("GET", setup.buyer().token(),
                "/api/v1/outlets/" + setup.buyer().outletId() + "/credit/summary", null, null);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("onlinePayEnabled").isMissingNode()).isFalse();
        assertThat(reply.data().get("onlinePayEnabled").asBoolean()).isFalse();
    }
}
