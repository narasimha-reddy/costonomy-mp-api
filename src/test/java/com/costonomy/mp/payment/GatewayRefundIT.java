package com.costonomy.mp.payment;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.GatewayRefundRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refunds of gateway payments that are not order payments (D-204): recorded before the gateway is asked, never more than
 * was captured, an unknown outcome looked up and never repeated blindly.
 */
@org.springframework.test.context.event.RecordApplicationEvents
class GatewayRefundIT extends AbstractIntegrationTest {

    @Autowired private GatewayRefundService refunds;
    @Autowired private GatewayRefundRepository repo;
    @Autowired private GatewayRefundProperties props;
    @Autowired private MockPaymentProvider gateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.test.context.event.ApplicationEvents appEvents;

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

    private String capturedPayment(String amount) {
        var intent = gateway.createAuthorization(new PaymentProvider.AuthorizationRequest(
                "ref-" + UUID.randomUUID(), new BigDecimal(amount), "INR", "test", UUID.randomUUID().toString(), true));
        return gateway.completeCheckout(intent.providerOrderId(), "upi").providerPaymentId();
    }

    private Request request(String paymentId, String captured, String amount, String key) {
        return new Request(GatewayRefund.FOOD_DELIVERY, 1L, "CANCELLED", paymentId, new BigDecimal(captured),
                new BigDecimal(amount), "test", key);
    }

    private GatewayRefund reload(Long id) {
        return repo.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("a refund is recorded first, then sent once; asking again moves no more money")
    void recordedThenSentOnce() {
        String pay = capturedPayment("100.00");
        String key = "k-" + UUID.randomUUID();

        var g = refunds.record(request(pay, "100.00", "40.00", key));
        assertThat(g.getStatus()).as("recorded before the gateway was asked").isEqualTo(GatewayRefund.REQUESTED);
        assertThat(gateway.refundedOf(pay)).isEqualByComparingTo("0");
        assertThat(refunds.record(request(pay, "100.00", "40.00", key)).getId()).as("the same request").isEqualTo(g.getId());

        assertThat(refunds.send(g.getId())).isEqualTo(Result.SUCCEEDED);
        assertThat(refunds.send(g.getId())).isEqualTo(Result.NOT_CLAIMED);

        assertThat(reload(g.getId()).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(reload(g.getId()).getProviderRefundId()).startsWith("mock_rfnd_");
        assertThat(gateway.refundedOf(pay)).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a key reused for a different refund is refused")
    void keyReuseIsRefused() {
        String pay = capturedPayment("100.00");
        String key = "k-" + UUID.randomUUID();
        refunds.record(request(pay, "100.00", "40.00", key));

        assertThatThrownBy(() -> refunds.record(request(pay, "100.00", "50.00", key)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("different refund");
    }

    @Test
    @DisplayName("partial refunds add up to no more than was captured")
    void neverMoreThanCaptured() {
        String pay = capturedPayment("100.00");
        refunds.record(request(pay, "100.00", "30.00", "adj-" + pay));
        refunds.record(request(pay, "100.00", "70.00", "rest-" + pay));

        assertThatThrownBy(() -> refunds.record(request(pay, "100.00", "1.00", "extra-" + pay)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("past the 100");
        assertThat(repo.findByProviderPaymentIdOrderByIdAsc(pay)).hasSize(2);
    }

    @Test
    @DisplayName("two requests that together exceed the capture cannot both be recorded")
    void concurrentRequestsCannotOverReserve() throws Exception {
        String pay = capturedPayment("100.00");
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return tryRecord(pay, "a"); });
            var b = pool.submit(() -> { start.await(); return tryRecord(pay, "b"); });
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(repo.findByProviderPaymentIdOrderByIdAsc(pay)).hasSize(1);
    }

    private boolean tryRecord(String pay, String who) {
        try {
            refunds.record(request(pay, "100.00", "60.00", "race-" + who + "-" + pay));
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    @Test
    @DisplayName("a refund the gateway never received is looked up, found absent, and sent again exactly once")
    void lostRequestIsSentAgain() {
        String pay = capturedPayment("100.00");
        var g = refunds.record(request(pay, "100.00", "100.00", "lost-req-" + pay));
        gateway.loseNextRefundRequest(PaymentProviderException.unreachable("timeout", new RuntimeException("x")));

        assertThat(refunds.send(g.getId())).isEqualTo(Result.AMBIGUOUS);
        assertThat(reload(g.getId()).getStatus()).isEqualTo(GatewayRefund.AMBIGUOUS);
        assertThat(refunds.send(g.getId())).as("not sent blindly while unknown").isEqualTo(Result.NOT_CLAIMED);

        assertThat(refunds.reconcile(g.getId())).isEqualTo(Result.FAILED);
        assertThat(refunds.send(g.getId())).isEqualTo(Result.SUCCEEDED);

        assertThat(gateway.refundedOf(pay)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a refund whose answer was lost is found on the gateway's list by its receipt and never repeated")
    void lostAnswerIsFoundNotRepeated() {
        String pay = capturedPayment("100.00");
        var g = refunds.record(request(pay, "100.00", "100.00", "lost-ans-" + pay));
        gateway.loseNextRefundAnswer(PaymentProviderException.unreachable("timeout", new RuntimeException("x")));

        assertThat(refunds.send(g.getId())).isEqualTo(Result.AMBIGUOUS);
        assertThat(gateway.refundedOf(pay)).as("the gateway did refund it").isEqualByComparingTo("100.00");

        assertThat(refunds.reconcile(g.getId())).isEqualTo(Result.SUCCEEDED);

        assertThat(reload(g.getId()).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(gateway.refundedOf(pay)).as("refunded once").isEqualByComparingTo("100.00");
        assertThat(gateway.listRefunds(pay)).hasSize(1);
    }

    @Test
    @DisplayName("an expired lease is treated as unknown and looked up, not resent")
    void expiredLeaseIsLookedUp() {
        String pay = capturedPayment("100.00");
        var g = refunds.record(request(pay, "100.00", "100.00", "lease-" + pay));
        gateway.loseNextRefundAnswer(PaymentProviderException.unreachable("timeout", new RuntimeException("x")));
        refunds.send(g.getId());
        // What a crashed worker leaves behind: SENDING under a lease that has run out.
        jdbc.update("update gateway_refund set status = 'SENDING', claim_token = 'dead', "
                + "claim_expires_at = date_sub(now(6), interval 1 minute) where id = ?", g.getId());

        assertThat(refunds.recoverExpiredLease(g.getId())).isTrue();
        assertThat(reload(g.getId()).getStatus()).isEqualTo(GatewayRefund.AMBIGUOUS);
        assertThat(refunds.reconcile(g.getId())).isEqualTo(Result.SUCCEEDED);
        assertThat(gateway.refundedOf(pay)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a refund is not sent if the gateway already shows more refunded than would fit")
    void externalRefundCountsTowardsTheCap() {
        String pay = capturedPayment("100.00");
        gateway.refundedExternally(pay, new BigDecimal("80.00"));
        var g = refunds.record(request(pay, "100.00", "30.00", "ext-" + pay));

        assertThat(refunds.send(g.getId())).isEqualTo(Result.EXHAUSTED);

        assertThat(reload(g.getId()).getLastError()).contains("already shows 80");
        assertThat(gateway.refundedOf(pay)).as("nothing more was sent").isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("a refusal waits for a person, who can retry it or record that it was made at the gateway")
    void refusalThenOperatorActions() {
        String pay = capturedPayment("100.00");
        var g = refunds.record(request(pay, "100.00", "50.00", "refuse-" + pay));
        gateway.loseNextRefundRequest(new PaymentProviderException("The refund was declined", false, "400"));

        assertThat(refunds.send(g.getId())).isEqualTo(Result.EXHAUSTED);
        assertThat(refunds.send(g.getId())).as("not retried by the sweep").isEqualTo(Result.NOT_CLAIMED);

        refunds.retry(g.getId());
        assertThat(refunds.send(g.getId())).isEqualTo(Result.SUCCEEDED);

        var h = refunds.record(request(pay, "100.00", "20.00", "ext2-" + pay));
        gateway.loseNextRefundRequest(new PaymentProviderException("The refund was declined", false, "400"));
        refunds.send(h.getId());
        refunds.resolveExternally(h.getId(), "gw-dashboard-1");
        assertThat(reload(h.getId()).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(reload(h.getId()).getProviderRefundId()).isEqualTo("gw-dashboard-1");
        assertThatThrownBy(() -> refunds.resolveExternally(h.getId(), "again")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a gateway that keeps failing the refund is retried a bounded number of times, then waits for a person")
    void boundedRetries() {
        var intent = gateway.createAuthorization(new PaymentProvider.AuthorizationRequest(
                "ref-" + UUID.randomUUID(), new BigDecimal("100.19"), "INR", "test", UUID.randomUUID().toString(), true));
        String pay = gateway.completeCheckout(intent.providerOrderId(), "upi").providerPaymentId();
        // The mock declines refunds of a payment whose captured amount ends in its failure suffix.
        var g = refunds.record(request(pay, "100.19", "50.00", "bounded-" + pay));

        Result last = null;
        for (int i = 0; i < props.getMaxAttempts(); i++) {
            last = refunds.send(g.getId());
        }

        assertThat(last).isEqualTo(Result.EXHAUSTED);
        assertThat(reload(g.getId()).getAttemptCount()).isEqualTo(props.getMaxAttempts());
        assertThat(reload(g.getId()).getLastError()).contains("REFUND_DECLINED");
    }

    @Test
    @DisplayName("the sweep sends what was recorded and not yet sent")
    void sweepSendsRecordedRefunds() {
        String pay = capturedPayment("100.00");
        var g = refunds.record(request(pay, "100.00", "25.00", "sweep-" + pay));
        jdbc.update("update gateway_refund set requested_at = date_sub(now(6), interval 5 minute) where id = ?", g.getId());

        var pass = refunds.sweep();

        assertThat(pass.sent()).isGreaterThanOrEqualTo(1);
        assertThat(reload(g.getId()).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(gateway.refundedOf(pay)).isEqualByComparingTo("25.00");
    }

    // ── A refund the gateway has accepted but not completed (D-206) ─────────

    private long outcomesFor(Long refundId) {
        return appEvents.stream(com.costonomy.mp.payment.service.GatewayRefundOutcome.class)
                .filter(e -> e.refundId().equals(refundId)).count();
    }

    @Test
    @DisplayName("a refund the gateway answers PENDING stays pending: id stored, nothing announced, until it completes")
    void immediatePendingIsNotASuccess() {
        String pay = capturedPayment("100.00");
        gateway.nextRefundsArePending(1);
        var g = refunds.record(request(pay, "100.00", "40.00", "k-" + UUID.randomUUID()));

        assertThat(refunds.send(g.getId())).isEqualTo(Result.PENDING);

        var pending = reload(g.getId());
        assertThat(pending.getStatus()).isEqualTo(GatewayRefund.PENDING);
        assertThat(pending.getProviderRefundId()).as("remembered, to ask about it").startsWith("mock_rfnd_");
        assertThat(pending.getCompletedAt()).isNull();
        assertThat(outcomesFor(g.getId())).as("no success is announced yet").isZero();
        assertThat(refunds.send(g.getId())).as("and it is not sent again").isEqualTo(Result.NOT_CLAIMED);
        assertThat(repo.sumUnfinished()).isGreaterThanOrEqualTo(new BigDecimal("40.00"));

        assertThat(refunds.reconcilePending(g.getId())).isEqualTo(Result.SUCCEEDED);

        assertThat(reload(g.getId()).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(reload(g.getId()).getCompletedAt()).isNotNull();
        assertThat(outcomesFor(g.getId())).as("announced once, on completion").isEqualTo(1);
        assertThat(refunds.reconcilePending(g.getId())).isEqualTo(Result.NOT_CLAIMED);
        assertThat(outcomesFor(g.getId())).isEqualTo(1);
        assertThat(gateway.listRefunds(pay)).hasSize(1);
    }

    @Test
    @DisplayName("a lost answer found by lookup as PENDING is pending, not succeeded, and is never sent again")
    void listedPendingIsNotASuccess() {
        String pay = capturedPayment("100.00");
        gateway.nextRefundsArePending(1);
        gateway.loseNextRefundAnswer(PaymentProviderException.unreachable("timeout", new RuntimeException("x")));
        var g = refunds.record(request(pay, "100.00", "30.00", "k-" + UUID.randomUUID()));
        assertThat(refunds.send(g.getId())).isEqualTo(Result.AMBIGUOUS);

        assertThat(refunds.reconcile(g.getId())).isEqualTo(Result.PENDING);

        var pending = reload(g.getId());
        assertThat(pending.getStatus()).isEqualTo(GatewayRefund.PENDING);
        assertThat(pending.getProviderRefundId()).isNotNull();
        assertThat(outcomesFor(g.getId())).isZero();
        assertThat(gateway.listRefunds(pay)).as("found, not repeated").hasSize(1);

        // Not due yet: it was only just asked about. Once it is, the sweep asks the gateway about pending refunds too.
        assertThat(refunds.sweep().pendingChecked()).isZero();
        jdbc.update("update gateway_refund set next_retry_at = utc_timestamp(6) - interval 1 minute where id = ?", g.getId());
        assertThat(refunds.sweep().pendingChecked()).isGreaterThanOrEqualTo(1);

        assertThat(reload(g.getId()).getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(outcomesFor(g.getId())).isEqualTo(1);
        assertThat(gateway.refundedOf(pay)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("a pending refund the bank later rejects becomes visible and is retried as a new refund, once")
    void pendingThatLaterFailsIsRetriedUnderANewKey() {
        String pay = capturedPayment("100.00");
        gateway.nextRefundsArePendingThenFail(1);
        var g = refunds.record(request(pay, "100.00", "50.00", "k-" + UUID.randomUUID()));
        assertThat(refunds.send(g.getId())).isEqualTo(Result.PENDING);
        String firstRefund = reload(g.getId()).getProviderRefundId();

        assertThat(refunds.reconcilePending(g.getId())).isEqualTo(Result.FAILED);

        var failed = reload(g.getId());
        assertThat(failed.getStatus()).as("visible and retryable, never 'done'").isEqualTo(GatewayRefund.FAILED);
        assertThat(failed.getLastError()).contains("failed").contains(firstRefund);
        assertThat(failed.getSendRound()).as("the gateway holds that send as failed: the retry is a new refund").isEqualTo(1);
        assertThat(failed.getProviderRefundId()).isNull();
        assertThat(failed.getCompletedAt()).isNull();
        assertThat(outcomesFor(g.getId())).as("a failed refund is not announced as returned").isZero();
        assertThat(gateway.refundedOf(pay)).as("the money did not go back").isEqualByComparingTo("0");
        assertThat(repo.inStatus(GatewayRefund.FAILED, 50)).extracting(GatewayRefund::getId).contains(g.getId());

        assertThat(refunds.send(g.getId())).isEqualTo(Result.SUCCEEDED);

        var done = reload(g.getId());
        assertThat(done.getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(done.getProviderRefundId()).as("a different refund from the failed one").isNotEqualTo(firstRefund);
        assertThat(outcomesFor(g.getId())).isEqualTo(1);
        assertThat(gateway.refundedOf(pay)).as("returned once").isEqualByComparingTo("50.00");
        assertThat(gateway.listRefunds(pay)).extracting(PaymentProvider.ProviderRefundEntry::receipt)
                .as("under two different receipts").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a refund still pending after the asking limit stays pending and shows up in the finance queue")
    void aStuckPendingRefundIsVisible() {
        String pay = capturedPayment("100.00");
        gateway.nextRefundsArePending(1);
        var g = refunds.record(request(pay, "100.00", "20.00", "k-" + UUID.randomUUID()));
        refunds.send(g.getId());
        jdbc.update("update gateway_refund set requested_at = now(6) - interval 3 hour where id = ?", g.getId());

        assertThat(refunds.queues(100).pending()).extracting(GatewayRefund::getId).contains(g.getId());
        assertThat(refunds.queues(100).pendingCount()).isGreaterThanOrEqualTo(1);
    }
}
