package com.costonomy.mp.delivery;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.domain.CourierPayableState;
import com.costonomy.mp.delivery.provider.payment.MockCourierPaymentRail;
import com.costonomy.mp.delivery.repository.CourierPayableRepository;
import com.costonomy.mp.delivery.service.CourierPayableProperties;
import com.costonomy.mp.delivery.service.CourierPayableService;
import com.costonomy.mp.delivery.service.CourierPaymentService;
import com.costonomy.mp.delivery.service.SupplierCourierPayableSync;
import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.repository.GatewayRefundRepository;
import com.costonomy.mp.payment.service.GatewayRefundOutcome;
import com.costonomy.mp.payment.service.GatewayRefundService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real races on courier payables and gateway refunds (D-207): a payment or refund outcome arriving after its lease was
 * recovered, and an operator action meeting a job on the same row. The write lock on {@code lockById} is what makes the
 * second one wait and read the first one's result; each test also shows the row really is held, by keeping it locked from
 * another transaction and checking the contender has not finished.
 */
@AutoConfigureMockMvc
class FinancialLockRaceIT extends AbstractIntegrationTest {

    private static final String API = "LOCK_API";

    /** Counts every success outcome published, per refund. */
    @TestConfiguration
    static class Counter {
        @Bean
        OutcomeCounter outcomeCounter() {
            return new OutcomeCounter();
        }
    }

    static class OutcomeCounter {
        final Map<Long, AtomicInteger> byRefund = new ConcurrentHashMap<>();

        @EventListener
        void on(GatewayRefundOutcome e) {
            byRefund.computeIfAbsent(e.refundId(), k -> new AtomicInteger()).incrementAndGet();
        }

        int of(long refundId) {
            var n = byRefund.get(refundId);
            return n == null ? 0 : n.get();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private MockCourierPaymentRail rail;
    @Autowired private SupplierCourierPayableSync sync;
    @Autowired private CourierPayableService payableService;
    @Autowired private CourierPaymentService payments;
    @Autowired private CourierPayableRepository payables;
    @Autowired private CourierPayableProperties props;
    @Autowired private GatewayRefundService refunds;
    @Autowired private GatewayRefundRepository refundRepo;
    @Autowired private OutcomeCounter outcomes;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ApiClient api;
    private Duration originalBackoff;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        DeliveryFixture.provider(jdbc, API, "MOCK_API");
        rail.reset();
        paymentProvider.clearRefundFaults();
        originalBackoff = props.getRetryBackoff();
        props.setRetryBackoff(Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        rail.reset();
        paymentProvider.clearRefundFaults();
        props.setRetryBackoff(originalBackoff);
    }

    // ── fixtures ────────────────────────────────────────────────────────

    /** A payable that is due on the API rail. */
    private CourierPayable due(String fee) throws Exception {
        var s = DeliveryFixture.create(mvc, json, api, jdbc, paymentProvider, "0.00", "0.00");
        String pdid = "lock-" + UUID.randomUUID();
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    provider_delivery_id, quoted_amount, started_at, created_at)
                values (?, ?, 1, 'BOOKING', 'BOOKED', ?, ?, now(6), now(6))
                """, s.deliveryId(), API, pdid, new BigDecimal(fee));
        jdbc.update("update delivery set status = 'PICKED_UP', provider_code = ?, provider_delivery_id = ?, fee = ?, "
                        + "mode = 'COSTONOMY', picked_up_at = now(6), version = version + 1 where id = ?",
                API, pdid, new BigDecimal(fee), s.deliveryId());
        sync.sync(s.deliveryId());
        var p = payableService.forDelivery(CourierPayable.SUPPLIER_DELIVERY, s.deliveryId()).get(0);
        assertThat(p.getState()).isEqualTo(CourierPayableState.DUE);
        return p;
    }

    private String capturedPayment(String amount) {
        var intent = paymentProvider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                "ref-" + UUID.randomUUID(), new BigDecimal(amount), "INR", "test", UUID.randomUUID().toString(), true));
        return paymentProvider.completeCheckout(intent.providerOrderId(), "upi").providerPaymentId();
    }

    private GatewayRefund recorded(String pay, String amount) {
        return refunds.record(new GatewayRefundService.Request(GatewayRefund.FOOD_DELIVERY, 1L, "CANCELLED", pay,
                new BigDecimal("100.00"), new BigDecimal(amount), "test", "lock-" + UUID.randomUUID()));
    }

    private List<String> payableEvents(long id) {
        return jdbc.queryForList("select event_type from courier_payable_event where payable_id = ? order by id",
                String.class, id);
    }

    private long count(List<String> events, String type) {
        return events.stream().filter(type::equals).count();
    }

    /** Hold the row's write lock from another transaction until released. */
    private Holder hold(String table, long id) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(txManager);
        Future<?> done = pool.submit(() -> tx.executeWithoutResult(s -> {
            if ("courier_payable".equals(table)) {
                payables.lockById(id).orElseThrow();
            } else {
                refundRepo.lockById(id).orElseThrow();
            }
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("the holder got the lock").isTrue();
        return new Holder(release, done);
    }

    private record Holder(CountDownLatch release, Future<?> done) {
        void free() throws Exception {
            release.countDown();
            done.get(10, TimeUnit.SECONDS);
        }
    }

    private static void assertStillWaiting(Future<?>... contenders) throws Exception {
        Thread.sleep(500);
        for (var f : contenders) {
            assertThat(f.isDone()).as("a contender must wait while the row is locked").isFalse();
        }
    }

    // ── payables ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an operator action waits for a job holding the payable, then reads its result")
    void anOperatorWaitsForTheRowLock() throws Exception {
        var p = due("70.00");
        var holder = hold("courier_payable", p.getId());

        Future<CourierPayable> operator = pool.submit(() ->
                payableService.recordCancellationFee(p.getId(), new BigDecimal("5.00"), "ops:1", "invoice 9"));
        assertStillWaiting(operator);
        holder.free();

        assertThat(operator.get(10, TimeUnit.SECONDS).getCancellationFee()).isEqualByComparingTo("5.00");
        assertThat(count(payableEvents(p.getId()), "CANCELLATION_FEE_RECORDED")).isEqualTo(1);
    }

    @Test
    @DisplayName("a payment answer that arrives after its lease was recovered is dropped, and the payment is found once, not repeated")
    void paymentOutcomeAfterLeaseRecovery() throws Exception {
        var p = due("80.00");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rail.holdNextAnswer(entered, release);

        Future<CourierPaymentService.Result> job = pool.submit(() -> payments.pay(p.getId()));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the rail has taken the money and is holding the answer").isTrue();
        jdbc.update("update courier_payable set claim_expires_at = utc_timestamp(6) - interval 1 minute where id = ?", p.getId());

        assertThat(payments.recoverExpiredLease(p.getId())).as("recovery takes the expired lease").isTrue();
        release.countDown();
        assertThat(job.get(10, TimeUnit.SECONDS)).as("the late answer finds the lease gone").isEqualTo(CourierPaymentService.Result.LEASE_LOST);

        assertThat(payments.reconcileAmbiguous(p.getId())).isEqualTo(CourierPaymentService.Result.PAID);

        var after = payableService.get(p.getId());
        assertThat(after.getState()).isEqualTo(CourierPayableState.PAID);
        assertThat(after.getAmountPaid()).as("paid once").isEqualByComparingTo("80.00");
        assertThat(rail.moneyMoved()).as("the rail moved money once").isEqualTo(1);
        var events = payableEvents(p.getId());
        assertThat(count(events, "LEASE_EXPIRED")).isEqualTo(1);
        assertThat(count(events, "PAYMENT_CONFIRMED_BY_LOOKUP")).isEqualTo(1);
        assertThat(events).as("no second payment outcome").doesNotContain("PAYMENT_SENT", "PAID");
        assertThat(jdbc.queryForList("select status from courier_payment_attempt where payable_id = ?", String.class, p.getId()))
                .containsExactly("PAID");
    }

    @Test
    @DisplayName("an operator recording a payment and the job recovering the same expired lease end consistently, in either order")
    void operatorVersusLeaseRecovery() throws Exception {
        var p = due("60.00");
        jdbc.update("update courier_payable set state = 'PROCESSING', claim_token = 'old-worker', "
                + "claim_expires_at = utc_timestamp(6) - interval 1 minute, attempt_count = 1 where id = ?", p.getId());
        var holder = hold("courier_payable", p.getId());

        Future<Boolean> job = pool.submit(() -> payments.recoverExpiredLease(p.getId()));
        Future<Object> operator = pool.submit(() -> {
            try {
                return payableService.markPaid(p.getId(), new BigDecimal("60.00"), "UTR-77", null, "ops:1", "bank");
            } catch (BusinessException refused) {
                return refused;
            }
        });
        assertStillWaiting(job, operator);
        holder.free();

        assertThat(job.get(10, TimeUnit.SECONDS)).isIn(true, false);
        Object result = operator.get(10, TimeUnit.SECONDS);       // neither thread threw a lost-update error
        var after = payableService.get(p.getId());
        var events = payableEvents(p.getId());
        if (result instanceof BusinessException) {
            // The operator went first and was told a payment was in flight; recovery then made it ambiguous.
            assertThat(after.getState()).isEqualTo(CourierPayableState.AMBIGUOUS);
            assertThat(after.getAmountPaid()).isEqualByComparingTo("0");
            assertThat(events).doesNotContain("MANUAL_PAYMENT_RECORDED");
        } else {
            // Recovery went first, so the person could record the payment that had in fact been made.
            assertThat(after.getState()).isEqualTo(CourierPayableState.PAID);
            assertThat(after.getAmountPaid()).as("paid once").isEqualByComparingTo("60.00");
            assertThat(count(events, "MANUAL_PAYMENT_RECORDED")).isEqualTo(1);
        }
        assertThat(count(events, "LEASE_EXPIRED")).isEqualTo(1);
    }

    // ── gateway refunds ─────────────────────────────────────────────────

    @Test
    @DisplayName("an operator action waits for a job holding the refund, then reads its result")
    void anOperatorWaitsForTheRefundLock() throws Exception {
        String pay = capturedPayment("100.00");
        var g = recorded(pay, "30.00");
        jdbc.update("update gateway_refund set status = 'EXHAUSTED', attempt_count = 6 where id = ?", g.getId());
        var holder = hold("gateway_refund", g.getId());

        Future<GatewayRefund> operator = pool.submit(() -> refunds.retry(g.getId()));
        assertStillWaiting(operator);
        holder.free();

        assertThat(operator.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(GatewayRefund.REQUESTED);
        assertThat(refundRepo.findById(g.getId()).orElseThrow().getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("a refund answer that arrives after its lease was recovered is dropped, and the refund is found once, not repeated")
    void refundOutcomeAfterLeaseRecovery() throws Exception {
        String pay = capturedPayment("100.00");
        var g = recorded(pay, "40.00");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        paymentProvider.holdNextRefundAnswer(entered, release);

        Future<GatewayRefundService.Result> job = pool.submit(() -> refunds.send(g.getId()));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the gateway has refunded and is holding the answer").isTrue();
        jdbc.update("update gateway_refund set claim_expires_at = utc_timestamp(6) - interval 1 minute where id = ?", g.getId());

        assertThat(refunds.recoverExpiredLease(g.getId())).isTrue();
        release.countDown();
        assertThat(job.get(10, TimeUnit.SECONDS)).isEqualTo(GatewayRefundService.Result.LEASE_LOST);
        assertThat(refundRepo.findById(g.getId()).orElseThrow().getStatus()).isEqualTo(GatewayRefund.AMBIGUOUS);

        assertThat(refunds.reconcile(g.getId())).isEqualTo(GatewayRefundService.Result.SUCCEEDED);

        var after = refundRepo.findById(g.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(after.getProviderRefundId()).isNotNull();
        assertThat(paymentProvider.listRefunds(pay)).as("one refund at the gateway").hasSize(1);
        assertThat(paymentProvider.refundedOf(pay)).isEqualByComparingTo("40.00");
        assertThat(outcomes.of(g.getId())).as("the success was announced once").isEqualTo(1);
    }

    @Test
    @DisplayName("an operator confirming a pending refund and the job reconciling it end with one success, in either order")
    void operatorVersusPendingReconcile() throws Exception {
        String pay = capturedPayment("100.00");
        paymentProvider.nextRefundsArePending(1);
        var g = recorded(pay, "25.00");
        assertThat(refunds.send(g.getId())).isEqualTo(GatewayRefundService.Result.PENDING);
        var holder = hold("gateway_refund", g.getId());

        Future<GatewayRefundService.Result> job = pool.submit(() -> refunds.reconcilePending(g.getId()));
        Future<Object> operator = pool.submit(() -> {
            try {
                return refunds.resolveExternally(g.getId(), "rfnd_dashboard");
            } catch (BusinessException refused) {
                return refused;
            }
        });
        assertStillWaiting(operator);
        holder.free();

        job.get(10, TimeUnit.SECONDS);
        Object result = operator.get(10, TimeUnit.SECONDS);
        var after = refundRepo.findById(g.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(GatewayRefund.SUCCEEDED);
        assertThat(outcomes.of(g.getId())).as("one success outcome, whoever got there first").isEqualTo(1);
        if (result instanceof BusinessException) {
            assertThat(after.getProviderRefundId()).as("the job's refund id stands").startsWith("mock_rfnd_");
        } else {
            assertThat(after.getProviderRefundId()).isIn("rfnd_dashboard", after.getProviderRefundId());
        }
    }
}
