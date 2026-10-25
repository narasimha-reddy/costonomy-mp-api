package com.costonomy.mp.payment;

import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.GatewayRefundRepository;
import com.costonomy.mp.payment.service.GatewayRefundOutcome;
import com.costonomy.mp.payment.service.GatewayRefundProperties;
import com.costonomy.mp.payment.service.GatewayRefundService;
import com.costonomy.mp.payment.service.GatewayRefundService.Request;
import com.costonomy.mp.payment.service.GatewayRefundService.Result;
import com.costonomy.mp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refund webhooks (D-207): the gateway's refund events move a gateway refund of ours only when they can be matched to it by
 * payment id plus refund id or receipt, only completion succeeds it, a duplicate or out-of-order event changes nothing, and
 * an event that cannot be matched is left to the poll.
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
class GatewayRefundWebhookIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private GatewayRefundService refunds;
    @Autowired private GatewayRefundRepository repo;
    @Autowired private GatewayRefundProperties props;
    @Autowired private MockPaymentProvider gateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEvents appEvents;

    private Duration originalBackoff;

    @BeforeEach
    void setUp() {
        gateway.clearRefundFaults();
        originalBackoff = props.getRetryBackoff();
        props.setRetryBackoff(Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        gateway.clearRefundFaults();
        props.setRetryBackoff(originalBackoff);
    }

    private String capturedPayment() {
        var intent = gateway.createAuthorization(new PaymentProvider.AuthorizationRequest(
                "ref-" + UUID.randomUUID(), new BigDecimal("100.00"), "INR", "test", UUID.randomUUID().toString(), true));
        return gateway.completeCheckout(intent.providerOrderId(), "upi").providerPaymentId();
    }

    private GatewayRefund recorded(String pay, String amount) {
        return refunds.record(new Request(GatewayRefund.FOOD_DELIVERY, 1L, "CANCELLED", pay, new BigDecimal("100.00"),
                new BigDecimal(amount), "test", "wh-" + UUID.randomUUID()));
    }

    /** A refund the gateway has accepted as PENDING and we have stored as such. */
    private GatewayRefund pending(String pay, String amount) {
        gateway.nextRefundsArePending(1);
        var g = recorded(pay, amount);
        assertThat(refunds.send(g.getId())).isEqualTo(Result.PENDING);
        return reload(g);
    }

    private GatewayRefund reload(GatewayRefund g) {
        return repo.findById(g.getId()).orElseThrow();
    }

    private long outcomes(GatewayRefund g) {
        return appEvents.stream(GatewayRefundOutcome.class).filter(e -> e.refundId().equals(g.getId())).count();
    }

    /** A signed refund event in Razorpay's shape: the refund under payload.refund.entity, the payment beside it. */
    private int event(String eventId, String type, String paymentId, String refundId, String receipt, String amountPaise)
            throws Exception {
        String entity = """
                {"id":%s,"payment_id":%s,"receipt":%s,"amount":%s,"status":"x"}
                """.formatted(q(refundId), q(paymentId), q(receipt), amountPaise == null ? "null" : amountPaise);
        String body = """
                {"id":"%s","event":"%s","payload":{"refund":{"entity":%s},"payment":{"entity":{"id":%s}}}}
                """.formatted(eventId, type, entity, q(paymentId));
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/razorpay")
                        .header("X-Razorpay-Signature", MockPaymentProvider.TEST_SIGNATURE)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private static String paise(String rupees) {
        return new BigDecimal(rupees).movePointRight(2).toPlainString();
    }

    @Test
    @DisplayName("created keeps it pending, processed completes it once; a repeat of either, or the same event again, changes nothing")
    void pendingThenProcessedAndDuplicates() throws Exception {
        String pay = capturedPayment();
        gateway.nextRefundsArePending(1);
        gateway.loseNextRefundAnswer(PaymentProviderException.unreachable("timeout", new RuntimeException("x")));
        var g = recorded(pay, "40.00");
        assertThat(refunds.send(g.getId())).isEqualTo(Result.AMBIGUOUS);
        String receipt = reload(g).receipt();
        String refundId = gateway.listRefunds(pay).get(0).providerRefundId();

        assertThat(event("e-1", "refund.created", pay, refundId, receipt, paise("40.00"))).isEqualTo(200);
        assertThat(reload(g).getStatus()).as("pending, not done").isEqualTo(GatewayRefund.PENDING);
        assertThat(reload(g).getProviderRefundId()).isEqualTo(refundId);
        assertThat(outcomes(g)).isZero();

        assertThat(event("e-2", "refund.processed", pay, refundId, receipt, paise("40.00"))).isEqualTo(200);
        assertThat(reload(g).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(reload(g).getCompletedAt()).isNotNull();
        assertThat(outcomes(g)).isEqualTo(1);

        assertThat(event("e-2", "refund.processed", pay, refundId, receipt, paise("40.00"))).as("same event id").isEqualTo(200);
        assertThat(event("e-3", "refund.processed", pay, refundId, receipt, paise("40.00"))).as("a second event for it").isEqualTo(200);
        assertThat(event("e-4", "refund.created", pay, refundId, receipt, paise("40.00"))).as("created after processed").isEqualTo(200);
        assertThat(reload(g).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(outcomes(g)).as("announced once").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_event where provider_event_id = 'e-2'",
                Integer.class)).as("the repeated event id was stored once").isEqualTo(1);
    }

    @Test
    @DisplayName("processed arriving before created still completes it, and the late created or a failed after success changes nothing")
    void outOfOrder() throws Exception {
        String pay = capturedPayment();
        var g = pending(pay, "35.00");
        String refundId = reload(g).getProviderRefundId();
        String receipt = reload(g).receipt();

        assertThat(event("o-1", "refund.processed", pay, refundId, receipt, paise("35.00"))).isEqualTo(200);
        assertThat(reload(g).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);

        event("o-2", "refund.created", pay, refundId, receipt, paise("35.00"));
        event("o-3", "refund.failed", pay, refundId, receipt, paise("35.00"));

        var after = reload(g);
        assertThat(after.getStatus()).as("a success is not undone by a stale failure").isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(after.getSendRound()).isZero();
        assertThat(outcomes(g)).isEqualTo(1);
    }

    @Test
    @DisplayName("failed makes a pending refund visible and retryable under a new key, once, however many times it is sent")
    void failedIsVisibleAndRetryable() throws Exception {
        String pay = capturedPayment();
        var g = pending(pay, "50.00");
        String refundId = reload(g).getProviderRefundId();
        String receipt = reload(g).receipt();

        assertThat(event("f-1", "refund.failed", pay, refundId, receipt, paise("50.00"))).isEqualTo(200);
        var failed = reload(g);
        assertThat(failed.getStatus()).isEqualTo(GatewayRefund.FAILED);
        assertThat(failed.getSendRound()).isEqualTo(1);
        assertThat(failed.getProviderRefundId()).isNull();
        assertThat(failed.getLastError()).contains("failed");
        assertThat(outcomes(g)).as("a failure is not announced as money returned").isZero();

        event("f-2", "refund.failed", pay, refundId, receipt, paise("50.00"));
        assertThat(reload(g).getSendRound()).as("a repeat does not advance the round again").isEqualTo(1);

        assertThat(refunds.send(g.getId())).as("the retry goes under the new key").isEqualTo(Result.SUCCEEDED);
        assertThat(reload(g).getProviderRefundId()).isNotEqualTo(refundId);
        assertThat(outcomes(g)).isEqualTo(1);

        // A late completion of the first send is not applied; it is flagged for a person.
        event("f-3", "refund.processed", pay, refundId, receipt, paise("50.00"));
        var flagged = reload(g);
        assertThat(flagged.getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(flagged.getLastError()).contains("double refund");
        assertThat(outcomes(g)).isEqualTo(1);
    }

    @Test
    @DisplayName("an event that cannot be matched to a refund of ours touches nothing: wrong ids, wrong payment, wrong amount, no ids")
    void unmatchedEventsAreIgnored() throws Exception {
        String pay = capturedPayment();
        var g = pending(pay, "20.00");
        String refundId = reload(g).getProviderRefundId();
        String receipt = reload(g).receipt();

        event("u-1", "refund.processed", pay, "rfnd_someone_else", "other-receipt", paise("20.00"));
        event("u-2", "refund.processed", pay, null, null, paise("20.00"));                       // payment id alone
        event("u-3", "refund.processed", capturedPayment(), refundId, receipt, paise("20.00"));  // right ids, wrong payment
        event("u-4", "refund.processed", pay, refundId, receipt, paise("21.00"));               // right ids, wrong amount
        event("u-5", "refund.processed", pay, refundId.toUpperCase(), null, paise("20.00"));     // not the same id

        var after = reload(g);
        assertThat(after.getStatus()).isEqualTo(GatewayRefund.PENDING);
        assertThat(outcomes(g)).isZero();

        // The poll is still the recovery: it completes it with no event at all.
        assertThat(refunds.reconcilePending(g.getId())).isEqualTo(Result.SUCCEEDED);
        assertThat(outcomes(g)).isEqualTo(1);
    }

    @Test
    @DisplayName("an event for a refund whose send is still in flight is left to that send, and the receipt alone can match a lost answer")
    void inFlightAndReceiptMatch() throws Exception {
        String pay = capturedPayment();
        var g = recorded(pay, "15.00");
        jdbc.update("update gateway_refund set status = 'SENDING', claim_token = 't', "
                + "claim_expires_at = utc_timestamp(6) + interval 5 minute where id = ?", g.getId());
        event("i-1", "refund.processed", pay, "rfnd_x", reload(g).receipt(), paise("15.00"));
        assertThat(reload(g).getStatus()).as("the in-flight send settles it").isEqualTo(GatewayRefund.SENDING);

        jdbc.update("update gateway_refund set status = 'AMBIGUOUS', claim_token = null, claim_expires_at = null where id = ?",
                g.getId());
        event("i-2", "refund.processed", pay, "rfnd_x", reload(g).receipt(), paise("15.00"));   // the id was never stored: the receipt matches
        var after = reload(g);
        assertThat(after.getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(after.getProviderRefundId()).isEqualTo("rfnd_x");
        assertThat(outcomes(g)).isEqualTo(1);
    }

    @Test
    @DisplayName("a refund event that is not one of these refunds, and speed changes, are accepted and change nothing")
    void foreignAndSpeedEvents() throws Exception {
        String pay = capturedPayment();
        var g = pending(pay, "10.00");

        assertThat(event("x-1", "refund.speed_changed", pay, reload(g).getProviderRefundId(), reload(g).receipt(), paise("10.00")))
                .isEqualTo(200);
        assertThat(event("x-2", "refund.created", pay, "rfnd_not_ours", "mandi-refund-123", paise("10.00"))).isEqualTo(200);

        assertThat(reload(g).getStatus()).isEqualTo(GatewayRefund.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_event where provider_event_id in ('x-1', 'x-2')",
                Integer.class)).isEqualTo(2);
    }
}
