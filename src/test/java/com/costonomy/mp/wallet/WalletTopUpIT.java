package com.costonomy.mp.wallet;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPayment;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefund;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderRefundStatus;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.service.WalletTopUpJobs;
import com.costonomy.mp.wallet.service.WalletTopUpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Adding money through Razorpay, end to end against the mock provider (D-107).
 *
 * <p>The property under test throughout is the one the feature exists for: every
 * captured payment ends as exactly one credit or exactly one refund, and the
 * wallet balance always equals the sum of its ledger. Assertions read the
 * database directly, not the API, so a response that says the right thing over a
 * wrong table would still fail.
 */
@AutoConfigureMockMvc
class WalletTopUpIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WalletTopUpJobs jobs;
    @Autowired private WalletTopUpService topUpService;
    @SpyBean private MockPaymentProvider provider;

    private WalletTopUpSupport t;

    @BeforeEach
    void setUp() {
        t = new WalletTopUpSupport(mvc, json, jdbc);
        reset(provider);
    }

    /** The customer completes Razorpay's checkout for this top-up; returns the payment id. */
    private String pay(com.fasterxml.jackson.databind.JsonNode topUp) {
        return provider.completeCheckout(topUp.get("razorpayOrderId").asText()).providerPaymentId();
    }

    /** Create, pay and confirm, expecting the wallet to be credited. */
    private long topUp(Buyer buyer, String amount) throws Exception {
        var created = t.createOk(buyer, amount);
        var confirmed = t.confirm(buyer, created.get("topUpId").asLong(), pay(created));
        assertThat(confirmed.status()).describedAs(confirmed.body().toString()).isEqualTo(200);
        return created.get("topUpId").asLong();
    }

    private void assertBooksBalance(Buyer buyer) {
        assertThat(t.balance(buyer)).isEqualByComparingTo(t.ledgerSum(buyer));
    }

    // ── Creating ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("creating a top-up")
    class Creating {

        @Test
        @DisplayName("opens a Razorpay order for exactly that amount, captured on payment, and records it")
        void createsOrder() throws Exception {
            var buyer = t.newBuyer();

            var reply = t.create(buyer, "500.00", "key-1");

            assertThat(reply.status()).isEqualTo(200);
            var data = reply.data();
            assertThat(data.get("topUpId").asLong()).isPositive();
            assertThat(data.get("razorpayOrderId").asText()).startsWith("mock_order_");
            assertThat(data.get("keyId").asText()).isEqualTo("mock_key");
            assertThat(data.get("amount").decimalValue()).isEqualByComparingTo("500.00");
            assertThat(data.get("currency").asText()).isEqualTo("INR");
            var row = jdbc.queryForMap("select status, razorpay_order_id, amount, created_by from wallet_top_up where id = ?",
                    data.get("topUpId").asLong());
            assertThat(row.get("status")).isEqualTo("CREATED");
            assertThat(row.get("razorpay_order_id")).isEqualTo(data.get("razorpayOrderId").asText());
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("500.00");
            // Nothing is credited by creating one.
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
            // Auto-capture was asked for: a top-up is not held for later.
            verify(provider).createAuthorization(org.mockito.ArgumentMatchers.argThat(request ->
                    request.autoCapture() && request.amount().compareTo(new BigDecimal("500.00")) == 0
                            && "INR".equals(request.currency())));
        }

        @Test
        @DisplayName("the same Idempotency-Key returns the same top-up and opens no second Razorpay order")
        void idempotentReplay() throws Exception {
            var buyer = t.newBuyer();

            var first = t.create(buyer, "500.00", "same-key");
            var again = t.create(buyer, "500.00", "same-key");

            assertThat(again.status()).isEqualTo(200);
            assertThat(again.data().get("topUpId").asLong()).isEqualTo(first.data().get("topUpId").asLong());
            assertThat(again.data().get("razorpayOrderId").asText())
                    .isEqualTo(first.data().get("razorpayOrderId").asText());
            assertThat(t.topUpRows(buyer)).isEqualTo(1);
            verify(provider, times(1)).createAuthorization(any());
        }

        @Test
        @DisplayName("after the idempotency record is gone, the row's own key still returns the same top-up")
        void replayAfterIdempotencyRecordExpired() throws Exception {
            var buyer = t.newBuyer();
            var first = t.create(buyer, "500.00", "expiring-key");
            jdbc.update("delete from idempotency_record where idempotency_key = ?", "expiring-key");

            var again = t.create(buyer, "500.00", "expiring-key");

            assertThat(again.status()).isEqualTo(200);
            assertThat(again.data().get("topUpId").asLong()).isEqualTo(first.data().get("topUpId").asLong());
            assertThat(t.topUpRows(buyer)).isEqualTo(1);
            verify(provider, times(1)).createAuthorization(any());
        }

        @Test
        @DisplayName("the same key with a different amount is refused, not answered with the first top-up")
        void keyReuseRefused() throws Exception {
            var buyer = t.newBuyer();
            t.create(buyer, "500.00", "reused");

            var reply = t.create(buyer, "900.00", "reused");

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
            assertThat(t.topUpRows(buyer)).isEqualTo(1);
        }

        @Test
        @DisplayName("the same key racing itself opens one order")
        void concurrentSameKey() throws Exception {
            var buyer = t.newBuyer();
            var pool = Executors.newFixedThreadPool(4);
            var start = new CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return t.create(buyer, "500.00", "racing-key").status();
                }));
            }
            start.countDown();
            for (var result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertThat(t.topUpRows(buyer)).isEqualTo(1);
            verify(provider, times(1)).createAuthorization(any());
        }

        @Test
        @DisplayName("a missing Idempotency-Key is refused")
        void keyRequired() throws Exception {
            var buyer = t.newBuyer();
            var reply = t.call("POST", buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-ups",
                    java.util.Map.of("amount", "500.00"), null);
            assertThat(reply.status()).isBetween(400, 499);
            assertThat(t.topUpRows(buyer)).isZero();
        }

        @Test
        @DisplayName("Razorpay refusing the order leaves a FAILED row, no money, and a clear error")
        void providerFailureAtCreation() throws Exception {
            var buyer = t.newBuyer();
            doThrow(new PaymentProviderException("Razorpay is unavailable", true, "503"))
                    .when(provider).createAuthorization(any());

            var reply = t.create(buyer, "500.00", "failing");

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.code()).isEqualTo("PAYMENT_FAILED");
            long id = jdbc.queryForObject("select id from wallet_top_up where outlet_id = ?", Long.class, buyer.outletId());
            assertThat(t.dbStatus(id)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select razorpay_order_id from wallet_top_up where id = ?", String.class, id)).isNull();
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
            assertThat(t.ledgerRows(id)).isZero();
            assertThat(t.status(buyer, id).data().get("status").asText()).isEqualTo("FAILED");

            // A fresh attempt works once Razorpay is back.
            reset(provider);
            assertThat(t.create(buyer, "500.00", "second-try").status()).isEqualTo(200);
        }

        @Test
        @DisplayName("a top-up whose creation crashed before Razorpay answered is closed by the poller, never payable")
        void neverOpenedRowIsClosed() throws Exception {
            var buyer = t.newBuyer();
            jdbc.update("""
                    insert into wallet_top_up (outlet_id, created_by, amount, status, idempotency_key,
                                               created_at, updated_at, version)
                    values (?, ?, 100, 'CREATED', ?, date_sub(utc_timestamp(6), interval 10 minute),
                            utc_timestamp(6), 0)
                    """, buyer.outletId(), t.userId(buyer.token()), "orphan-" + UUID.randomUUID());
            long id = jdbc.queryForObject("select max(id) from wallet_top_up where outlet_id = ?", Long.class, buyer.outletId());

            topUpService.reconcile(id);

            assertThat(t.dbStatus(id)).isEqualTo("FAILED");
            verify(provider, never()).findPaymentForOrder(anyString());
        }
    }

    // ── Amount and limits at creation ────────────────────────────────────

    @Nested
    @DisplayName("amounts and limits when creating")
    class Amounts {

        @Test
        @DisplayName("below the minimum, above the maximum, zero, negative and sub-paise amounts are refused")
        void badAmounts() throws Exception {
            var buyer = t.newBuyer();
            for (String bad : List.of("9.99", "100000.01", "0", "-5", "10.001", "0.00")) {
                var reply = t.create(buyer, bad, UUID.randomUUID().toString());
                assertThat(reply.status()).describedAs(bad).isBetween(400, 422);
            }
            assertThat(t.topUpRows(buyer)).isZero();
            verify(provider, never()).createAuthorization(any());
        }

        @Test
        @DisplayName("the exact minimum and maximum are accepted")
        void boundaries() throws Exception {
            var buyer = t.newBuyer();
            assertThat(t.create(buyer, "10.00", "min").status()).isEqualTo(200);
            assertThat(t.create(buyer, "100000.00", "max").status()).isEqualTo(200);
        }

        @Test
        @DisplayName("a top-up that would take the wallet past its maximum is refused, naming what is left")
        void maxBalanceAtCreate() throws Exception {
            var buyer = t.newBuyer();
            topUp(buyer, "99999.99");
            assertThat(t.balance(buyer)).isEqualByComparingTo("99999.99");

            var reply = t.create(buyer, "10.00", UUID.randomUUID().toString());

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.code()).isEqualTo("WALLET_LIMIT_EXCEEDED");
            assertThat(reply.body().at("/error/message").asText()).contains("100000").contains("0.01");
            assertThat(t.topUpRows(buyer)).isEqualTo(1);
        }

        @Test
        @DisplayName("a top-up that lands exactly on the maximum is allowed")
        void exactlyAtMaxBalance() throws Exception {
            var buyer = t.newBuyer();
            topUp(buyer, "99990.00");
            topUp(buyer, "10.00");
            assertThat(t.balance(buyer)).isEqualByComparingTo("100000.00");
            assertBooksBalance(buyer);
        }
    }

    // ── Confirming ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("confirming a payment")
    class Confirming {

        @Test
        @DisplayName("credits the wallet once, writes one TOP_UP ledger row, and returns the wallet")
        void happyPath() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();

            var reply = t.confirm(buyer, id, pay(created));

            assertThat(reply.status()).isEqualTo(200);
            var wallet = reply.data();
            assertThat(wallet.get("outletId").asLong()).isEqualTo(buyer.outletId());
            assertThat(wallet.get("balance").decimalValue()).isEqualByComparingTo("500.00");
            assertThat(wallet.at("/recent/0/kind").asText()).isEqualTo("TOP_UP");
            assertThat(wallet.at("/limits/addedThisMonth").decimalValue()).isEqualByComparingTo("500.00");
            assertThat(wallet.at("/limits/remainingThisMonth").decimalValue()).isEqualByComparingTo("999500.00");

            assertThat(t.balance(buyer)).isEqualByComparingTo("500.00");
            assertThat(t.dbStatus(id)).isEqualTo("CREDITED");
            assertThat(t.ledgerRows(id)).isEqualTo(1);
            var entry = jdbc.queryForMap(
                    "select kind, direction, amount, balance_after from wallet_transaction where reference = ?", "topup-" + id);
            assertThat(entry.get("kind")).isEqualTo("TOP_UP");
            assertThat(entry.get("direction")).isEqualTo("CREDIT");
            assertThat((BigDecimal) entry.get("amount")).isEqualByComparingTo("500.00");
            assertThat((BigDecimal) entry.get("balance_after")).isEqualByComparingTo("500.00");
            assertBooksBalance(buyer);
            assertThat(t.status(buyer, id).data().get("status").asText()).isEqualTo("CREDITED");
            assertThat(t.status(buyer, id).data().get("creditedAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("odd amounts are credited to the paisa: 10.10, 99999.99, 0.29-style figures")
        void precision() throws Exception {
            var a = t.newBuyer();
            topUp(a, "10.10");
            assertThat(t.balance(a)).isEqualTo(new BigDecimal("10.1000"));
            assertThat(t.ledgerSum(a)).isEqualByComparingTo("10.10");

            var b = t.newBuyer();
            topUp(b, "99999.99");
            assertThat(t.balance(b)).isEqualTo(new BigDecimal("99999.9900"));

            var c = t.newBuyer();
            topUp(c, "1234.57");
            topUp(c, "10.03");
            assertThat(t.balance(c)).isEqualTo(new BigDecimal("1244.6000"));
            assertBooksBalance(a);
            assertBooksBalance(b);
            assertBooksBalance(c);
        }

        @Test
        @DisplayName("confirming twice credits once")
        void twiceSequentially() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);

            var first = t.confirm(buyer, id, paymentId);
            var second = t.confirm(buyer, id, paymentId);

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.data().get("balance").decimalValue()).isEqualByComparingTo("500.00");
            assertThat(t.balance(buyer)).isEqualByComparingTo("500.00");
            assertThat(t.ledgerRows(id)).isEqualTo(1);
            assertBooksBalance(buyer);
        }

        @Test
        @DisplayName("many confirms at once credit exactly once, five races")
        void concurrentConfirms() throws Exception {
            for (int race = 0; race < 5; race++) {
                var buyer = t.newBuyer();
                var created = t.createOk(buyer, "250.00");
                long id = created.get("topUpId").asLong();
                String paymentId = pay(created);

                var statuses = runAtOnce(8, () -> t.confirm(buyer, id, paymentId).status());

                assertThat(statuses).allMatch(status -> status == 200);
                assertThat(t.balance(buyer)).isEqualByComparingTo("250.00");
                assertThat(t.ledgerRows(id)).isEqualTo(1);
                assertThat(t.dbStatus(id)).isEqualTo("CREDITED");
                assertBooksBalance(buyer);
            }
        }

        @Test
        @DisplayName("a confirm racing the poller credits once, five races")
        void confirmRacingPoller() throws Exception {
            for (int race = 0; race < 5; race++) {
                var buyer = t.newBuyer();
                var created = t.createOk(buyer, "250.00");
                long id = created.get("topUpId").asLong();
                String paymentId = pay(created);
                t.backdate(id, "created_at", 5);

                var pool = Executors.newFixedThreadPool(2);
                var start = new CountDownLatch(1);
                var confirm = pool.submit(() -> {
                    start.await();
                    return t.confirm(buyer, id, paymentId).status();
                });
                var poll = pool.submit(() -> {
                    start.await();
                    topUpService.reconcile(id);
                    return 0;
                });
                start.countDown();
                assertThat(confirm.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                poll.get(30, TimeUnit.SECONDS);
                pool.shutdown();

                assertThat(t.balance(buyer)).isEqualByComparingTo("250.00");
                assertThat(t.ledgerRows(id)).isEqualTo(1);
                assertBooksBalance(buyer);
            }
        }

        @Test
        @DisplayName("a wrong signature is refused before Razorpay is even asked, and nothing is credited")
        void badSignature() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();

            var reply = t.confirm(buyer, id, pay(created), "not-the-signature");

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
            verify(provider, never()).fetchPayment(anyString());
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
            assertThat(t.dbStatus(id)).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("a bad signature on an already-credited top-up does not read as success")
        void badSignatureAfterCredit() throws Exception {
            var buyer = t.newBuyer();
            long id = topUp(buyer, "500.00");

            var reply = t.confirm(buyer, id, "pay_whatever", "forged");

            assertThat(reply.status()).isEqualTo(400);
            assertThat(t.ledgerRows(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("a real payment for another top-up's order is refused and credits neither")
        void paymentForAnotherOrder() throws Exception {
            var buyer = t.newBuyer();
            var mine = t.createOk(buyer, "500.00");
            var other = t.createOk(buyer, "500.00");
            String otherPayment = pay(other);

            var reply = t.confirm(buyer, mine.get("topUpId").asLong(), otherPayment);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
            assertThat(t.dbStatus(mine.get("topUpId").asLong())).isEqualTo("CREATED");
            assertThat(t.dbStatus(other.get("topUpId").asLong())).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("a payment for a different amount than the top-up is refused; nothing is credited")
        void wrongAmount() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);
            doReturn(new ProviderPayment(paymentId, created.get("razorpayOrderId").asText(),
                    ProviderPaymentStatus.CAPTURED, new BigDecimal("400.00"), new BigDecimal("400.00"), null, null))
                    .when(provider).fetchPayment(paymentId);

            var reply = t.confirm(buyer, id, paymentId);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
            assertThat(t.ledgerRows(id)).isZero();
            assertThat(t.dbStatus(id)).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("a payment that is not captured yet answers TOP_UP_PROCESSING, credits nothing, and can be confirmed later")
        void notCaptured() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);
            doReturn(new ProviderPayment(paymentId, created.get("razorpayOrderId").asText(),
                    ProviderPaymentStatus.AUTHORIZED, new BigDecimal("500.00"), BigDecimal.ZERO, null, null))
                    .when(provider).fetchPayment(paymentId);

            var reply = t.confirm(buyer, id, paymentId);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("TOP_UP_PROCESSING");
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
            assertThat(t.dbStatus(id)).isEqualTo("CREATED");
            assertThat(t.status(buyer, id).data().get("status").asText()).isEqualTo("CREATED");

            // It clears at Razorpay: the same confirm, retried, now credits.
            reset(provider);
            assertThat(t.confirm(buyer, id, paymentId).status()).isEqualTo(200);
            assertThat(t.balance(buyer)).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("Razorpay unreachable during confirm is 'still processing', and the poller then credits the payment")
        void providerDownDuringConfirm() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(provider).fetchPayment(paymentId);

            var reply = t.confirm(buyer, id, paymentId);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("TOP_UP_PROCESSING");
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");

            reset(provider);
            t.backdate(id, "created_at", 5);
            topUpService.reconcile(id);
            assertThat(t.balance(buyer)).isEqualByComparingTo("500.00");
            assertThat(t.ledgerRows(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("a payment id Razorpay does not know is refused and credits nothing")
        void unknownPayment() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");

            var reply = t.confirm(buyer, created.get("topUpId").asLong(), "pay_doesnotexist");

            assertThat(reply.status()).isEqualTo(400);
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a declined attempt credits nothing, and the order can still be paid afterwards")
        void declinedThenPaid() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();
            var declined = provider.declineAttempt(created.get("razorpayOrderId").asText());

            var reply = t.confirm(buyer, id, declined.providerPaymentId());
            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.code()).isEqualTo("PAYMENT_FAILED");
            assertThat(t.dbStatus(id)).isEqualTo("CREATED");

            assertThat(t.confirm(buyer, id, pay(created)).status()).isEqualTo(200);
            assertThat(t.balance(buyer)).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("confirming a FAILED top-up is refused")
        void failedTopUp() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);
            jdbc.update("update wallet_top_up set status = 'FAILED' where id = ?", id);

            var reply = t.confirm(buyer, id, paymentId);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("validation: blank or malformed ids and signatures are refused")
        void malformedBody() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();

            assertThat(t.confirm(buyer, id, "", "sig").status()).isEqualTo(400);
            assertThat(t.confirm(buyer, id, "pay_1", "").status()).isEqualTo(400);
            assertThat(t.confirm(buyer, id, "pay 1/../x", "sig").status()).isEqualTo(400);
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
        }
    }

    // ── The poller ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("the background poller")
    class Poller {

        @Test
        @DisplayName("credits a captured payment whose confirm never arrived")
        void creditsUnconfirmed() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            pay(created);
            t.backdate(id, "created_at", 5);

            jobs.poll();

            assertThat(t.dbStatus(id)).isEqualTo("CREDITED");
            assertThat(t.balance(buyer)).isEqualByComparingTo("700.00");
            assertThat(t.ledgerRows(id)).isEqualTo(1);
            assertBooksBalance(buyer);
        }

        @Test
        @DisplayName("leaves a top-up alone in its first minute, so the client's own confirm gets the first go")
        void leavesFreshOnesAlone() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            pay(created);

            jobs.poll();

            assertThat(t.dbStatus(id)).isEqualTo("CREATED");
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("running twice credits once")
        void pollingTwice() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            pay(created);
            t.backdate(id, "created_at", 5);

            jobs.poll();
            jobs.poll();
            topUpService.reconcile(id);

            assertThat(t.balance(buyer)).isEqualByComparingTo("700.00");
            assertThat(t.ledgerRows(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("does not ask Razorpay about the same unpaid top-up on every run")
        void backsOff() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            t.backdate(id, "created_at", 5);

            jobs.poll();
            jobs.poll();
            jobs.poll();

            verify(provider, times(1)).findPaymentForOrder(created.get("razorpayOrderId").asText());
            assertThat(t.dbStatus(id)).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("a payment still only authorised is left waiting, not credited and not expired")
        void authorizedIsLeftWaiting() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);
            doReturn(java.util.Optional.of(new ProviderPayment(paymentId, created.get("razorpayOrderId").asText(),
                    ProviderPaymentStatus.AUTHORIZED, new BigDecimal("700.00"), BigDecimal.ZERO, null, null)))
                    .when(provider).findPaymentForOrder(created.get("razorpayOrderId").asText());
            t.backdate(id, "created_at", 3 * 24 * 60);

            topUpService.reconcile(id);

            assertThat(t.dbStatus(id)).isEqualTo("CREATED");
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an abandoned top-up is EXPIRED after a day, once Razorpay confirms nothing was paid")
        void expiresAbandoned() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            t.backdate(id, "created_at", 2 * 24 * 60);

            topUpService.reconcile(id);

            verify(provider).findPaymentForOrder(created.get("razorpayOrderId").asText());
            assertThat(t.dbStatus(id)).isEqualTo("EXPIRED");
            assertThat(t.status(buyer, id).data().get("status").asText()).isEqualTo("EXPIRED");
            assertThat(t.balance(buyer)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a day-old top-up that was in fact paid is credited, not expired")
        void expiredButPaidIsCredited() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            pay(created);
            t.backdate(id, "created_at", 2 * 24 * 60);

            topUpService.reconcile(id);

            assertThat(t.dbStatus(id)).isEqualTo("CREDITED");
            assertThat(t.balance(buyer)).isEqualByComparingTo("700.00");
            assertThat(t.ledgerRows(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("a payment that turns up after the top-up was EXPIRED is still credited, once")
        void latePaymentAfterExpiry() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            t.backdate(id, "created_at", 2 * 24 * 60);
            topUpService.reconcile(id);
            assertThat(t.dbStatus(id)).isEqualTo("EXPIRED");

            var reply = t.confirm(buyer, id, pay(created));

            assertThat(reply.status()).isEqualTo(200);
            assertThat(t.dbStatus(id)).isEqualTo("CREDITED");
            assertThat(t.balance(buyer)).isEqualByComparingTo("700.00");
            assertThat(t.confirm(buyer, id, pay(created)).status()).isEqualTo(200);
            assertThat(t.ledgerRows(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("Razorpay being down leaves the top-up CREATED to be asked about again")
        void providerDownLeavesItForNextRun() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "700.00");
            long id = created.get("topUpId").asLong();
            pay(created);
            t.backdate(id, "created_at", 5);
            doThrow(PaymentProviderException.unreachable("down", new RuntimeException()))
                    .when(provider).findPaymentForOrder(anyString());

            topUpService.reconcile(id);
            assertThat(t.dbStatus(id)).isEqualTo("CREATED");

            reset(provider);
            topUpService.reconcile(id);
            assertThat(t.dbStatus(id)).isEqualTo("CREDITED");
        }
    }

    // ── Limits at credit time ────────────────────────────────────────────

    @Nested
    @DisplayName("a payment that would break a limit when it lands")
    class CreditTimeLimits {

        /** Two 60,000 top-ups are each fine alone and together break the 100,000 cap. */
        private record Pair(Buyer buyer, long a, String paymentA, long b, String paymentB) {
        }

        private Pair twoCreatedBeforeEitherPays(String amountA, String amountB) throws Exception {
            var buyer = t.newBuyer();
            var a = t.createOk(buyer, amountA);
            var b = t.createOk(buyer, amountB);
            return new Pair(buyer, a.get("topUpId").asLong(), pay(a), b.get("topUpId").asLong(), pay(b));
        }

        @Test
        @DisplayName("is refunded to its source, not credited, not dropped; the first top-up stands")
        void refundedNotCredited() throws Exception {
            var p = twoCreatedBeforeEitherPays("60000.00", "60000.00");
            assertThat(t.confirm(p.buyer(), p.a(), p.paymentA()).status()).isEqualTo(200);

            var reply = t.confirm(p.buyer(), p.b(), p.paymentB());

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.code()).isEqualTo("WALLET_LIMIT_EXCEEDED");
            assertThat(t.balance(p.buyer())).isEqualByComparingTo("60000.00");
            assertThat(t.ledgerRows(p.b())).isZero();
            assertThat(t.dbStatus(p.b())).isEqualTo("REFUNDED");
            assertThat(jdbc.queryForObject("select provider_refund_id from wallet_top_up where id = ?",
                    String.class, p.b())).startsWith("mock_rfnd_");
            // The refund went against B's own payment, for B's whole amount, once.
            verify(provider, times(1)).refund(eq(p.paymentB()), org.mockito.ArgumentMatchers.argThat(
                    amount -> amount.compareTo(new BigDecimal("60000.00")) == 0), anyString(), any());
            // The client is told REFUNDED and the limit is the reason.
            assertThat(t.status(p.buyer(), p.b()).data().get("status").asText()).isEqualTo("REFUNDED");
            assertBooksBalance(p.buyer());
        }

        @Test
        @DisplayName("confirming the refunded top-up again returns the same answer and refunds nothing more")
        void confirmingAgainRefundsNothingMore() throws Exception {
            var p = twoCreatedBeforeEitherPays("60000.00", "60000.00");
            t.confirm(p.buyer(), p.a(), p.paymentA());
            t.confirm(p.buyer(), p.b(), p.paymentB());

            var again = t.confirm(p.buyer(), p.b(), p.paymentB());

            assertThat(again.status()).isEqualTo(422);
            assertThat(again.code()).isEqualTo("WALLET_LIMIT_EXCEEDED");
            verify(provider, times(1)).refund(anyString(), any(), anyString(), any());
            assertThat(t.balance(p.buyer())).isEqualByComparingTo("60000.00");
        }

        @Test
        @DisplayName("the poller reaches the same end: refunded, not credited")
        void pollerRefunds() throws Exception {
            var p = twoCreatedBeforeEitherPays("60000.00", "60000.00");
            t.confirm(p.buyer(), p.a(), p.paymentA());
            t.backdate(p.b(), "created_at", 5);

            topUpService.reconcile(p.b());

            assertThat(t.dbStatus(p.b())).isEqualTo("REFUNDED");
            assertThat(t.balance(p.buyer())).isEqualByComparingTo("60000.00");
            assertThat(t.ledgerRows(p.b())).isZero();
        }

        @Test
        @DisplayName("two top-ups confirmed at the same instant: exactly one credited, the other refunded, five races")
        void raceForTheLastRoom() throws Exception {
            for (int race = 0; race < 5; race++) {
                var p = twoCreatedBeforeEitherPays("60000.00", "60000.00");

                var pool = Executors.newFixedThreadPool(2);
                var start = new CountDownLatch(1);
                var x = pool.submit(() -> {
                    start.await();
                    return t.confirm(p.buyer(), p.a(), p.paymentA()).status();
                });
                var y = pool.submit(() -> {
                    start.await();
                    return t.confirm(p.buyer(), p.b(), p.paymentB()).status();
                });
                start.countDown();
                var outcomes = List.of(x.get(60, TimeUnit.SECONDS), y.get(60, TimeUnit.SECONDS));
                pool.shutdown();

                assertThat(outcomes).containsExactlyInAnyOrder(200, 422);
                assertThat(t.balance(p.buyer())).isEqualByComparingTo("60000.00");
                var statuses = List.of(t.dbStatus(p.a()), t.dbStatus(p.b()));
                assertThat(statuses).containsExactlyInAnyOrder("CREDITED", "REFUNDED");
                assertThat(t.ledgerRows(p.a()) + t.ledgerRows(p.b())).isEqualTo(1);
                assertBooksBalance(p.buyer());
            }
        }

        @Test
        @DisplayName("a refund Razorpay refuses stays REFUND_PENDING with the money accounted for, and lands on retry")
        void refundRefusedThenRetried() throws Exception {
            // .19 on the captured amount makes the mock refuse the refund.
            var p = twoCreatedBeforeEitherPays("60000.00", "60000.19");
            t.confirm(p.buyer(), p.a(), p.paymentA());

            var reply = t.confirm(p.buyer(), p.b(), p.paymentB());

            assertThat(reply.code()).isEqualTo("WALLET_LIMIT_EXCEEDED");
            assertThat(t.dbStatus(p.b())).isEqualTo("REFUND_PENDING");
            assertThat(t.balance(p.buyer())).isEqualByComparingTo("60000.00");
            assertThat(t.ledgerRows(p.b())).isZero();
            assertThat(jdbc.queryForObject("select refund_attempts from wallet_top_up where id = ?",
                    Integer.class, p.b())).isEqualTo(1);
            // The API does not invent a state the contract does not have.
            assertThat(t.status(p.buyer(), p.b()).data().get("status").asText()).isEqualTo("CREATED");

            // Razorpay recovers; the refund job sends it again and it lands.
            reset(provider);
            doReturn(new ProviderRefund("rfnd_test", ProviderRefundStatus.COMPLETED,
                    new BigDecimal("60000.19"), null, null))
                    .when(provider).refund(eq(p.paymentB()), any(), anyString(), any());
            t.backdate(p.b(), "checked_at", 5);
            jobs.refunds();

            assertThat(t.dbStatus(p.b())).isEqualTo("REFUNDED");
            assertThat(t.balance(p.buyer())).isEqualByComparingTo("60000.00");
        }

        @Test
        @DisplayName("a refund Razorpay accepts as pending is followed up until it completes")
        void pendingRefundFollowedUp() throws Exception {
            // .23 makes the mock accept the refund as PENDING.
            var p = twoCreatedBeforeEitherPays("60000.00", "60000.23");
            t.confirm(p.buyer(), p.a(), p.paymentA());

            t.confirm(p.buyer(), p.b(), p.paymentB());

            assertThat(t.dbStatus(p.b())).isEqualTo("REFUND_PENDING");
            assertThat(jdbc.queryForObject("select provider_refund_id from wallet_top_up where id = ?",
                    String.class, p.b())).isNotNull();
            assertThat(jdbc.queryForObject("select refund_attempts from wallet_top_up where id = ?",
                    Integer.class, p.b())).isZero();

            t.backdate(p.b(), "checked_at", 5);
            jobs.refunds();

            assertThat(t.dbStatus(p.b())).isEqualTo("REFUNDED");
            // Followed up by asking, never by sending a second refund.
            verify(provider, times(1)).refund(anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("a refund that keeps failing is retried a bounded number of times, then left for a person")
        void refundRetriesAreBounded() throws Exception {
            var p = twoCreatedBeforeEitherPays("60000.00", "60000.19");
            t.confirm(p.buyer(), p.a(), p.paymentA());
            t.confirm(p.buyer(), p.b(), p.paymentB());

            for (int run = 0; run < 8; run++) {
                t.backdate(p.b(), "checked_at", 5);
                jobs.refunds();
            }

            assertThat(t.dbStatus(p.b())).isEqualTo("REFUND_PENDING");
            assertThat(jdbc.queryForObject("select refund_attempts from wallet_top_up where id = ?",
                    Integer.class, p.b())).isEqualTo(5);
            assertThat(t.balance(p.buyer())).isEqualByComparingTo("60000.00");
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("the wallet carries its limits; a new outlet has used none of the month")
        void walletLimits() throws Exception {
            var buyer = t.newBuyer();

            var limits = t.wallet(buyer).data().get("limits");

            assertThat(limits.get("maxBalance").decimalValue()).isEqualByComparingTo("100000");
            assertThat(limits.get("monthlyTopUpLimit").decimalValue()).isEqualByComparingTo("1000000");
            assertThat(limits.get("addedThisMonth").decimalValue()).isEqualByComparingTo("0");
            assertThat(limits.get("remainingThisMonth").decimalValue()).isEqualByComparingTo("1000000");
            assertThat(limits.get("minTopUp").decimalValue()).isEqualByComparingTo("10");
            assertThat(limits.get("maxTopUp").decimalValue()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("only credited top-ups count toward the month: created, expired and refunded ones do not")
        void onlyCreditedCounts() throws Exception {
            var buyer = t.newBuyer();
            topUp(buyer, "300.00");
            t.createOk(buyer, "999.00");
            var expired = t.createOk(buyer, "888.00");
            t.backdate(expired.get("topUpId").asLong(), "created_at", 2 * 24 * 60);
            topUpService.reconcile(expired.get("topUpId").asLong());

            var limits = t.wallet(buyer).data().get("limits");

            assertThat(limits.get("addedThisMonth").decimalValue()).isEqualByComparingTo("300.00");
            assertThat(limits.get("remainingThisMonth").decimalValue()).isEqualByComparingTo("999700.00");
        }

        @Test
        @DisplayName("a credit from last month (Asia/Kolkata) is not this month's")
        void lastMonthNotCounted() throws Exception {
            var buyer = t.newBuyer();
            long id = topUp(buyer, "300.00");
            var startOfThisMonth = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).withDayOfMonth(1)
                    .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            // A minute before the month began, in IST.
            t.creditedAt(startOfThisMonth, -60, id);
            assertThat(t.wallet(buyer).data().at("/limits/addedThisMonth").decimalValue()).isEqualByComparingTo("0");

            // And a minute after it began, it is.
            t.creditedAt(startOfThisMonth, 60, id);
            assertThat(t.wallet(buyer).data().at("/limits/addedThisMonth").decimalValue()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("the mock top-up still works, returns limits, and does not count toward the month")
        void mockTopUpStillWorks() throws Exception {
            var buyer = t.newBuyer();

            var reply = t.call("POST", buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up",
                    java.util.Map.of("amount", "250.00"), null);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("balance").decimalValue()).isEqualByComparingTo("250.00");
            assertThat(reply.data().at("/limits/addedThisMonth").decimalValue()).isEqualByComparingTo("0");

            // Mixed with a real one, the books still balance.
            topUp(buyer, "10.10");
            assertThat(t.balance(buyer)).isEqualByComparingTo("260.10");
            assertBooksBalance(buyer);
            assertThat(t.wallet(buyer).data().at("/limits/addedThisMonth").decimalValue()).isEqualByComparingTo("10.10");
        }

        @Test
        @DisplayName("a top-up's status shows its state and amount")
        void statusShape() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "42.50");

            var status = t.status(buyer, created.get("topUpId").asLong()).data();

            assertThat(status.get("topUpId").asLong()).isEqualTo(created.get("topUpId").asLong());
            assertThat(status.get("status").asText()).isEqualTo("CREATED");
            assertThat(status.get("amount").decimalValue()).isEqualByComparingTo("42.50");
            assertThat(status.get("currency").asText()).isEqualTo("INR");
            assertThat(status.get("razorpayOrderId").asText()).isEqualTo(created.get("razorpayOrderId").asText());
        }
    }

    // ── Access ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("who may do what")
    class Access {

        @Test
        @DisplayName("another restaurant cannot create, confirm or read a top-up, and nothing is credited")
        void otherTenantRefused() throws Exception {
            var owner = t.newBuyer();
            var stranger = t.newBuyer();
            var created = t.createOk(owner, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);

            assertThat(t.create(stranger.token(), owner.outletId(), "100.00", "k1").status()).isEqualTo(404);
            assertThat(t.confirm(stranger.token(), owner.outletId(), id, paymentId,
                    MockPaymentProvider.TEST_SIGNATURE).status()).isEqualTo(404);
            assertThat(t.call("GET", stranger.token(),
                    "/api/v1/outlets/" + owner.outletId() + "/wallet/top-ups/" + id, null, null).status()).isEqualTo(404);
            assertThat(t.topUpRows(owner)).isEqualTo(1);
            assertThat(t.balance(owner)).isEqualByComparingTo("0");
            assertThat(t.dbStatus(id)).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("your own outlet's path cannot reach another outlet's top-up id")
        void topUpIdFromAnotherOutlet() throws Exception {
            var owner = t.newBuyer();
            var stranger = t.newBuyer();
            var created = t.createOk(owner, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);

            // The stranger's own outlet in the path, the owner's top-up in the id.
            assertThat(t.confirm(stranger, id, paymentId).status()).isEqualTo(404);
            assertThat(t.status(stranger, id).status()).isEqualTo(404);
            assertThat(t.balance(stranger)).isEqualByComparingTo("0");
            assertThat(t.balance(owner)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a member without PROCUREMENT_SUBMIT cannot create or confirm")
        void memberWithoutPermissionRefused() throws Exception {
            var owner = t.newBuyer();
            var created = t.createOk(owner, "500.00");
            long id = created.get("topUpId").asLong();
            String paymentId = pay(created);
            String staffToken = t.api().loginFresh();
            t.grant(t.userId(staffToken), owner.outletId(), "REST_PROCUREMENT_STAFF");

            assertThat(t.create(staffToken, owner.outletId(), "100.00", "staff-key").status()).isEqualTo(404);
            assertThat(t.confirm(staffToken, owner.outletId(), id, paymentId,
                    MockPaymentProvider.TEST_SIGNATURE).status()).isEqualTo(404);
            assertThat(t.topUpRows(owner)).isEqualTo(1);
            assertThat(t.balance(owner)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("unauthenticated requests are refused")
        void unauthenticated() throws Exception {
            var reply = t.call("POST", "nope", "/api/v1/outlets/1/wallet/top-ups",
                    java.util.Map.of("amount", "500.00"), "k");
            assertThat(reply.status()).isEqualTo(401);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Runs {@code call} on {@code n} threads released together; returns what each returned. */
    private <T> List<T> runAtOnce(int n, Callable<T> call) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (var future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
