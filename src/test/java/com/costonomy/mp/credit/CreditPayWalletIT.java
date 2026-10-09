package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Paying whole invoices from the wallet through the credit-pay endpoint (D-196): applied in the same call, one wallet
 * debit and one repayment and payout per supplier, with the refusals leaving nothing moved.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayWalletIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @Autowired private CreditJobs creditJobs;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        reset(provider);
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private List<Map<String, Object>> debits(CreditPaySupport.Setup setup) {
        return jdbc.queryForList("select t.* from wallet_transaction t join wallet w on w.id = t.wallet_id "
                + "where w.outlet_id = ? and t.kind = 'CREDIT_REPAYMENT' order by t.id", setup.buyer().outletId());
    }

    @Test
    @DisplayName("wallet over 3 invoices at 2 suppliers: PAID at once, one wallet debit, repayment and payout per supplier, no Razorpay")
    void walletAcrossTwoSuppliers() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        p.setStoreRate(setup.line(0), "2.5");
        p.setStoreRate(setup.line(1), "4");
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        var d = reply.data();
        long id = d.get("attemptId").asLong();
        assertThat(d.get("status").asText()).isEqualTo("PAID");
        assertThat(d.get("method").asText()).isEqualTo("WALLET");
        assertThat(d.get("paidWith").asText()).isEqualTo("WALLET");
        assertThat(d.get("amount").decimalValue()).isEqualByComparingTo("12480.00");
        assertThat(d.get("feeAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.get("totalCharged").decimalValue()).isEqualByComparingTo("12480.00");
        assertThat(d.get("walletBalanceAfter").decimalValue()).isEqualByComparingTo("7520.00");
        assertThat(d.get("razorpayOrderId").isNull()).isTrue();
        assertThat(d.get("keyId").isNull()).isTrue();
        assertThat(d.get("paidAt").isNull()).isFalse();
        assertThat(d.get("expiresAt").isNull()).isTrue();
        for (var inv : d.at("/invoices")) {
            assertThat(inv.get("outcome").asText()).isEqualTo("PAID");
        }
        assertThat(d.at("/suppliers")).hasSize(2);

        var row = p.attempt(id);
        assertThat(row.get("method")).isEqualTo("WALLET");
        assertThat(row.get("channel")).isEqualTo("WALLET");
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(row.get("payment_method")).isEqualTo("WALLET");
        assertThat(row.get("razorpay_order_id")).isNull();
        assertThat(row.get("razorpay_payment_id")).isNull();
        assertThat(row.get("open_selection_key")).isNull();
        assertThat(row.get("paid_at")).isNotNull();
        assertThat((BigDecimal) row.get("fee_amount")).isEqualByComparingTo("0");

        // One wallet debit per supplier, each naming the supplier (D-158), none for the sum.
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("7520.00");
        var debits = debits(setup);
        assertThat(debits).hasSize(2);
        assertThat(debits.stream().map(t -> (BigDecimal) t.get("amount")).sorted().toList())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1980.00"), new BigDecimal("10500.00"));
        for (var debit : debits) {
            assertThat(debit.get("direction")).isEqualTo("DEBIT");
            assertThat((String) debit.get("reason")).isEqualTo("Credit repayment to ABC store");
        }

        // One repayment per supplier, WALLET source, tied to its debit and to the attempt; one payout each.
        var repayments = jdbc.queryForList("select * from credit_repayment where credit_payment_attempt_id = ? "
                + "order by credit_agreement_id", id);
        assertThat(repayments).hasSize(2);
        for (var r : repayments) {
            assertThat(r.get("source")).isEqualTo("WALLET");
            assertThat(r.get("wallet_transaction_id")).isNotNull();
            assertThat(r.get("provider_payment_id")).isNull();
            assertThat((String) r.get("idempotency_key")).startsWith("wallet-pay:" + id + ":");
        }
        assertThat((BigDecimal) repayments.get(0).get("amount")).isEqualByComparingTo("10500.00");
        assertThat((BigDecimal) repayments.get(1).get("amount")).isEqualByComparingTo("1980.00");
        var payout1 = jdbc.queryForMap("select * from credit_repayment_payout where credit_repayment_id = ?",
                repayments.get(0).get("id"));
        var payout2 = jdbc.queryForMap("select * from credit_repayment_payout where credit_repayment_id = ?",
                repayments.get(1).get("id"));
        assertThat((BigDecimal) payout1.get("commission_amount")).isEqualByComparingTo("262.50");
        assertThat((BigDecimal) payout2.get("commission_amount")).isEqualByComparingTo("79.20");
        assertThat(payout1.get("status")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from credit_payment pay join credit_repayment r "
                + "on r.id = pay.credit_repayment_id where r.credit_payment_attempt_id = ? and pay.source = 'WALLET' "
                + "and pay.method = 'WALLET'", Long.class, id)).isEqualTo(3L);

        // The invoices and the lines.
        for (var inv : setup.invoices()) {
            assertThat(p.invoiceStatus(inv)).isEqualTo("PAID");
        }
        assertThat(p.utilized(setup.line(0))).isEqualByComparingTo("0");
        assertThat(p.utilized(setup.line(1))).isEqualByComparingTo("0");
        p.assertAppliedOnce(setup, id, 2, 3);
        assertThat(p.s.snapshot(setup.line(0)).walletRows()).isEqualTo(before.lines().get(0).walletRows() + 2);

        // Supplier told once each; the restaurant not (it saw the result).
        assertThat(p.events("CreditRepaymentReceived", id)).isEqualTo(2);
        assertThat(p.events("CreditPaymentCompleted", id)).isZero();
        assertThat(p.events("CreditPaymentRefund", id)).isZero();
        var payload = json.readTree(jdbc.queryForList("select payload from outbox_event "
                + "where event_type = 'CreditRepaymentReceived' and json_extract(payload, '$.attemptId') = ?", id)
                .get(0).get("payload").toString());
        assertThat(payload.get("methodLabel").asText()).isEqualTo("wallet");
        assertThat(p.auditsFor("CREDIT_PAY_ATTEMPT_PAID", id)).isEqualTo(1);

        // No Razorpay, and the result can be read back.
        verify(provider, times(0)).createAuthorization(any());
        assertThat(p.status(setup.buyer(), id).data().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a wallet short of the total: 422 WALLET_INSUFFICIENT_BALANCE with shortBy and balance, and nothing moves")
    void short_() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "9000.00");
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
        assertThat(reply.body().at("/error/message").asText()).isEqualTo("You're ₹3480.00 short.");
        assertThat(reply.body().at("/error/details/shortBy").decimalValue()).isEqualByComparingTo("3480.00");
        assertThat(reply.body().at("/error/details/balance").decimalValue()).isEqualByComparingTo("9000.00");
        assertThat(p.snapshot(setup)).isEqualTo(before);
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("9000.00");
    }

    @Test
    @DisplayName("a restaurant that never added money is short by the whole total")
    void noWallet() throws Exception {
        var setup = p.single("500.00");
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
        assertThat(reply.body().at("/error/details/shortBy").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("a wallet on hold: 403 WALLET_ON_HOLD, nothing moves")
    void onHold() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        jdbc.update("update wallet set status = 'ON_HOLD' where outlet_id = ?", setup.buyer().outletId());
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(403);
        assertThat(reply.code()).isEqualTo("WALLET_ON_HOLD");
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("a wrong outstanding is 409 CREDIT_PAY_SELECTION_CHANGED naming the invoice; a deactivated store is 422; nothing moves either way")
    void selectionRefusals() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var before = p.snapshot(setup);

        var stale = p.create(setup.buyer(), List.of(p.items(setup.inv(0)).get(0),
                CreditPaySupport.item(setup.inv(1).id(), new BigDecimal("3999.00"))), "WALLET");

        assertThat(stale.status()).describedAs(stale.body().toString()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("CREDIT_PAY_SELECTION_CHANGED");
        assertThat(stale.body().at("/error/details/changedInvoiceIds/0").asLong()).isEqualTo(setup.inv(1).id());
        assertThat(p.snapshot(setup)).isEqualTo(before);

        p.deactivateStore(setup.line(1));
        var unavailable = p.create(setup.buyer(), p.allItems(setup), "WALLET");
        assertThat(unavailable.status()).describedAs(unavailable.body().toString()).isEqualTo(422);
        assertThat(unavailable.code()).isEqualTo("CREDIT_PAY_SUPPLIER_UNAVAILABLE");
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("the selection is checked again under the locks: an invoice changed while the call waited is 409 and nothing moves")
    void changedUnderTheLock() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var items = p.allItems(setup);
        var before = p.snapshot(setup);
        // Someone holds invoice 1 and takes 1000 off it before letting go.
        var holder = new Holder(
                "select id from credit_invoice where id = " + setup.inv(1).id() + " for update",
                "update credit_invoice set paid_amount = 1000, status = 'PARTIALLY_PAID', version = version + 1 "
                        + "where id = " + setup.inv(1).id(),
                "update credit_agreement set utilized_amount = utilized_amount - 1000 where id = "
                        + setup.line(0).agreementId());

        var call = pool.submit(() -> p.create(setup.buyer(), items, "WALLET"));
        Thread.sleep(1500);
        assertThat(call.isDone()).describedAs("the payment must wait for the invoice").isFalse();
        holder.release();

        var reply = call.get(30, TimeUnit.SECONDS);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("CREDIT_PAY_SELECTION_CHANGED");
        assertThat(reply.body().at("/error/details/changedInvoiceIds/0").asLong()).isEqualTo(setup.inv(1).id());
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("20000.00");
        assertThat(p.s.snapshot(setup.line(0)).repayments()).isZero();
        assertThat(p.snapshot(setup).lines().get(1)).isEqualTo(before.lines().get(1));
        assertThat(debits(setup)).isEmpty();
    }

    @Test
    @DisplayName("the wallet is locked before any invoice: while the wallet is held, the payment has not touched the invoices")
    void lockOrderWalletThenInvoices() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var items = p.allItems(setup);
        var holder = new Holder("select id from wallet where outlet_id = " + setup.buyer().outletId() + " for update");

        var call = pool.submit(() -> p.create(setup.buyer(), items, "WALLET"));
        Thread.sleep(1500);
        assertThat(call.isDone()).describedAs("the payment must wait for the wallet").isFalse();
        // An invoice can be locked and released meanwhile, so the payment is not holding any invoice.
        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("set session innodb_lock_wait_timeout = 3");
                st.execute("select id from credit_invoice where id = " + setup.inv(0).id() + " for update");
            }
            c.rollback();
        }
        holder.release();

        var reply = call.get(30, TimeUnit.SECONDS);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
    }

    @Test
    @DisplayName("the same key again returns the same attempt and the wallet is debited once")
    void replay() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var items = p.allItems(setup);

        var first = p.create(setup.buyer(), items, "WALLET", "wallet-key");
        var again = p.create(setup.buyer(), items, "WALLET", "wallet-key");

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).describedAs(again.body().toString()).isIn(200, 201);
        assertThat(again.data().get("attemptId").asLong()).isEqualTo(first.data().get("attemptId").asLong());
        assertThat(again.data().get("status").asText()).isEqualTo("PAID");
        assertThat(debits(setup)).hasSize(2);
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("7520.00");
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?", setup.buyer().outletId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two calls with the same key at the same moment: one debit set, one attempt")
    void concurrentSameKey() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var items = p.allItems(setup);
        List<Callable<Reply>> calls = List.of(() -> p.create(setup.buyer(), items, "WALLET", "race-key"),
                () -> p.create(setup.buyer(), items, "WALLET", "race-key"));

        var replies = p.concurrently(calls);

        for (var reply : replies) {
            // 500: the platform's IdempotencyStore.claim loses its own race with a poisoned session (a known gap
            // outside this feature, QUESTIONS-INT-API 1). The loser is refused, never run: the money asserts below hold.
            assertThat(reply.status()).describedAs(reply.body().toString()).isIn(200, 201, 409, 500);
            if (reply.status() == 409) {
                assertThat(reply.code()).isEqualTo("IDEMPOTENT_REQUEST_IN_PROGRESS");
            }
            if (reply.status() == 500) {
                assertThat(reply.code()).isEqualTo("INTERNAL_ERROR");
            }
        }
        assertThat(replies.stream().filter(r -> r.status() < 300).count()).isGreaterThanOrEqualTo(1);
        assertThat(debits(setup)).hasSize(2);
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("7520.00");
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?", setup.buyer().outletId()))
                .isEqualTo(1);
        p.assertAppliedOnce(setup, replies.get(0).status() < 300
                ? replies.get(0).data().get("attemptId").asLong() : replies.get(1).data().get("attemptId").asLong(), 2, 3);
    }

    @Test
    @DisplayName("two taps with different keys on one selection: one pays, the other finds the invoices settled (409); debited once")
    void concurrentDifferentKeys() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "30000.00");
        var items = p.allItems(setup);
        List<Callable<Reply>> calls = List.of(() -> p.create(setup.buyer(), items, "WALLET"),
                () -> p.create(setup.buyer(), items, "WALLET"));

        var replies = p.concurrently(calls);

        assertThat(replies.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        var loser = replies.stream().filter(r -> r.status() != 201).findFirst().orElseThrow();
        assertThat(loser.status()).describedAs(loser.body().toString()).isEqualTo(409);
        assertThat(loser.code()).isEqualTo("CREDIT_PAY_SELECTION_CHANGED");
        assertThat(debits(setup)).hasSize(2);
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("17520.00");
        assertThat(p.count("select count(*) from credit_payment_attempt where outlet_id = ?", setup.buyer().outletId()))
                .describedAs("a refusal under the lock writes no attempt").isEqualTo(1);
    }

    @Test
    @DisplayName("the wallet has no minimum: an invoice with 0.50 left is paid from it")
    void belowOnlineMinimumIsFine() throws Exception {
        var setup = p.single("100.00");
        assertThat(p.supplierReceipt(setup.inv(0), "99.50").status()).isEqualTo(200);
        p.s.topUp(setup.buyer(), "10.00");

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(p.invoiceStatus(setup.inv(0))).isEqualTo("PAID");
        assertThat(p.s.balance(setup.buyer())).isEqualByComparingTo("9.50");
    }

    @Test
    @DisplayName("paying an overdue invoice from the wallet clears the SYSTEM suspension, as a wallet repayment does")
    void systemSuspensionLifted() throws Exception {
        var buyer = p.s.newBuyer();
        var line = p.s.creditLine(buyer, "200000", Map.of("maxOverdueAmount", "1000"));
        var inv = new CreditPaySupport.Inv(line, p.invoiceOf(line, "4000.00"));
        var setup = new CreditPaySupport.Setup(buyer, List.of(line), List.of(inv));
        p.s.topUp(buyer, "5000.00");
        p.s.age(inv.id(), 10);
        creditJobs.sweepOverdue();
        assertThat(p.e.agreementRow(line.agreementId()).get("status")).isEqualTo("SUSPENDED");

        assertThat(p.create(buyer, p.allItems(setup), "WALLET").status()).isEqualTo(201);

        assertThat(p.e.agreementRow(line.agreementId()).get("status")).isEqualTo("ACTIVE");
        assertThat(p.agreementEvents("CreditReinstated", line.agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a wallet attempt cannot be confirmed with a Razorpay payment: 409 'That payment has ended'")
    void walletAttemptCannotBeVerified() throws Exception {
        var setup = p.single("500.00");
        p.s.topUp(setup.buyer(), "1000.00");
        long id = p.createOk(setup.buyer(), p.allItems(setup), "WALLET");

        var reply = p.verify(setup.buyer(), id, "mock_pay_x");

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(reply.body().at("/error/message").asText()).isEqualTo("That payment has ended. Start a new one.");
    }

    // ── a row-locking helper, as in CreditWalletRepayIT ──────────────────

    private final class Holder {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> done;

        Holder(String... sqls) {
            done = pool.submit(() -> {
                try (Connection c = jdbc.getDataSource().getConnection()) {
                    c.setAutoCommit(false);
                    try (var st = c.createStatement()) {
                        for (String sql : sqls) {
                            st.execute(sql);
                        }
                    }
                    held.countDown();
                    release.await();
                    c.commit();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                return null;
            });
            try {
                assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
        }

        void release() throws Exception {
            release.countDown();
            done.get(20, TimeUnit.SECONDS);
        }
    }
}
