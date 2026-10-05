package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditInvoiceService;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.payment.domain.RefundReason;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.payment.service.RefundService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestCheckout;
import com.costonomy.mp.support.TestOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * Races between the supplier's levers, the restaurant's money and the sweep (R12, R13, R15, R33).
 *
 * <p>Every assertion is on the FINAL STATE, never on who got there first, so a legal interleaving in either order
 * passes and an illegal one fails. Each race runs three times in a loop with a fresh fixture. R12 and R15 also have
 * a deterministic form: the loser is held at the exact statement where the race is decided.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditRaceConditionsIT extends AbstractIntegrationTest {

    private static final int ROUNDS = 3;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private RefundService refundService;

    /** Spied only to hold the overdue sweep between its read and its write (R15). */
    @SpyBean private CreditInvoiceService invoiceSpy;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        pool = Executors.newFixedThreadPool(4, r -> new Thread(r, "race-" + UUID.randomUUID()));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** Holds a connection with an open transaction until {@link #release()}; runs {@code sqls} after taking it. */
    private final class Holder {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> done;

        Holder(String... sqls) throws Exception {
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
            assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();
        }

        void release() throws Exception {
            release.countDown();
            done.get(20, TimeUnit.SECONDS);
        }
    }

    private long reservations(Line line, String... statuses) {
        return e.count("select count(*) from credit_reservation where credit_agreement_id = ? and status in ("
                + String.join(",", java.util.Collections.nCopies(statuses.length, "?")) + ")",
                concat(line.agreementId(), statuses));
    }

    private static Object[] concat(Object first, Object[] rest) {
        var all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private void assertIdentity(Line line) {
        var row = e.agreementRow(line.agreementId());
        assertThat(e.dec(row, "reserved_amount").add(e.dec(row, "utilized_amount")))
                .describedAs("reserved + utilized must never exceed the limit")
                .isLessThanOrEqualTo(e.dec(row, "approved_limit"));
        assertThat(e.dec(row, "reserved_amount").signum()).isGreaterThanOrEqualTo(0);
        assertThat(e.dec(row, "utilized_amount").signum()).isGreaterThanOrEqualTo(0);
    }

    // ── R12 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R12 (deterministic): the suspension commits while the order waits at the reservation statement; the order is refused and nothing is drawn")
    void suspensionCommittedBeforeTheReservationRefusesTheOrder() throws Exception {
        var line = s.creditLine("100000");
        long intent = e.answeredRequest(line, "100", 10);
        // The supplier's suspension is written but not yet committed: it holds the agreement row.
        var holder = new Holder("update credit_agreement set status = 'SUSPENDED', suspension_source = 'SUPPLIER', "
                + "suspended_at = now(6), version = version + 1 where id = " + line.agreementId());

        var order = pool.submit(() -> e.orderOnCredit(line.buyer(), intent));
        Thread.sleep(1500);
        assertThat(order.isDone()).describedAs("the order must be waiting on the agreement row").isFalse();
        holder.release();

        var reply = order.get(30, TimeUnit.SECONDS);
        assertThat(reply.status()).describedAs("refused, never funded: " + reply.body()).isIn(409, 422);
        var row = e.agreementRow(line.agreementId());
        assertThat(row.get("status")).isEqualTo("SUSPENDED");
        assertThat(e.dec(row, "reserved_amount")).isEqualByComparingTo("0");
        assertThat(e.dec(row, "utilized_amount")).isEqualByComparingTo("0");
        assertThat(reservations(line, "RESERVED", "UTILIZED")).isZero();
        assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ?", line.agreementId()))
                .isZero();
    }

    @Test
    @DisplayName("R12: a supplier suspending while the restaurant orders, three rounds: never an order on a suspended line that was suspended first")
    void suspendRacingAnOrder() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var line = s.creditLine("100000");
            long intent = e.answeredRequest(line, "100", 10);
            var start = new CountDownLatch(1);
            Future<Reply> order = pool.submit(() -> {
                start.await();
                return e.orderOnCredit(line.buyer(), intent);
            });
            Future<Reply> suspend = pool.submit(() -> {
                start.await();
                return e.suspend(line, "Account under review");
            });
            start.countDown();
            var orderReply = order.get(30, TimeUnit.SECONDS);
            var suspendReply = suspend.get(30, TimeUnit.SECONDS);

            var row = e.agreementRow(line.agreementId());
            assertThat(suspendReply.status())
                    .describedAs("round %d: suspend is 200 or a clean conflict, never a 5xx: %s", round, suspendReply.body())
                    .isIn(200, 409);
            assertThat(row.get("status")).describedAs("round %d: the line is suspended exactly when the suspend said so", round)
                    .isEqualTo(suspendReply.status() == 200 ? "SUSPENDED" : "ACTIVE");
            if (orderReply.status() == 200 || orderReply.status() == 201) {
                // The order got its credit first: it is fully drawn, with its invoice.
                assertThat(e.dec(row, "utilized_amount")).isEqualByComparingTo("1000.00");
                assertThat(reservations(line, "UTILIZED")).isEqualTo(1);
                assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ?",
                        line.agreementId())).isEqualTo(1);
            } else {
                // Refused: it can only be because the suspension came first, and then nothing at all was drawn.
                assertThat(orderReply.status()).describedAs(orderReply.body().toString()).isIn(409, 422);
                assertThat(suspendReply.status()).isEqualTo(200);
                assertThat(e.dec(row, "utilized_amount")).isEqualByComparingTo("0");
                assertThat(reservations(line, "RESERVED", "UTILIZED")).isZero();
                assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ?",
                        line.agreementId())).isZero();
            }
            assertThat(e.dec(row, "reserved_amount")).isEqualByComparingTo("0");
            assertIdentity(line);
        }
    }

    // ── R13 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R13: the limit cut to what is committed racing an order for more, three rounds: exactly one wins and exposure never passes the limit")
    void limitCutRacingAnOrder() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var line = s.creditLine("10000");
            long first = e.answeredRequest(line, "600", 10);
            assertThat(e.orderOnCredit(line.buyer(), first).status()).isIn(200, 201);
            long second = e.answeredRequest(line, "300", 10); // 3000 more: fits 10000, not 6000
            var start = new CountDownLatch(1);
            Future<Reply> order = pool.submit(() -> {
                start.await();
                return e.orderOnCredit(line.buyer(), second);
            });
            Future<Reply> cut = pool.submit(() -> {
                start.await();
                return e.modifyLimit(line, "6000"); // exactly the exposure committed before the race
            });
            start.countDown();
            var orderReply = order.get(30, TimeUnit.SECONDS);
            var cutReply = cut.get(30, TimeUnit.SECONDS);

            var row = e.agreementRow(line.agreementId());
            boolean ordered = orderReply.status() == 200 || orderReply.status() == 201;
            boolean cutApplied = cutReply.status() == 200;
            assertThat(ordered ^ cutApplied)
                    .describedAs("round %d: exactly one of the order (%d %s) and the cut (%d %s) wins", round,
                            orderReply.status(), orderReply.body(), cutReply.status(), cutReply.body())
                    .isTrue();
            if (ordered) {
                assertThat(cutReply.status()).describedAs(cutReply.body().toString()).isIn(400, 409);
                assertThat(e.dec(row, "approved_limit")).isEqualByComparingTo("10000");
                assertThat(e.dec(row, "utilized_amount")).isEqualByComparingTo("9000.00");
            } else {
                assertThat(orderReply.status()).describedAs(orderReply.body().toString()).isIn(409, 422);
                assertThat(e.dec(row, "approved_limit")).isEqualByComparingTo("6000");
                assertThat(e.dec(row, "utilized_amount")).isEqualByComparingTo("6000.00");
            }
            assertThat(e.dec(row, "reserved_amount")).isEqualByComparingTo("0");
            assertIdentity(line);
        }
    }

    // ── R15 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R15: the auto-suspend sweep has read the overdue figure when a repayment clears it below the maximum; no stale suspension, three rounds")
    void autoSuspendRacingARepayment() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var line = s.creditLine("500000", Map.of("maxOverdueAmount", "10000"));
            long invoice = s.invoice(line, "400", 100); // 40000
            s.topUp(line.buyer(), "40000.00");
            s.age(invoice, 10);

            var reached = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            var armed = new AtomicBoolean(true);
            doAnswer(call -> {
                Object dues = call.callRealMethod();
                if (armed.get() && Thread.currentThread().getName().startsWith("race-")
                        && ((Long) call.getArgument(0)).equals(line.agreementId())) {
                    reached.countDown();
                    if (!proceed.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the sweep was never released");
                    }
                }
                return dues;
            }).when(invoiceSpy).duesFor(anyLong());

            Future<?> sweep = pool.submit(() -> {
                creditJobs.sweepOverdue();
                return null;
            });
            assertThat(reached.await(60, TimeUnit.SECONDS)).describedAs("the sweep reached the overdue read").isTrue();
            // The sweep now holds "40000 overdue, maximum 10000". The restaurant repays 35000 on the request thread.
            var repay = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                    Map.of("amount", "35000.00"));
            assertThat(repay.status()).describedAs(repay.body().toString()).isEqualTo(201);
            proceed.countDown();
            sweep.get(30, TimeUnit.SECONDS);
            armed.set(false);

            var row = e.agreementRow(line.agreementId());
            assertThat(row.get("status")).describedAs("round %d: 5000 is overdue, the maximum is 10000: not suspended", round)
                    .isEqualTo("ACTIVE");
            assertThat(row.get("suspension_source")).isNull();
            assertThat(e.count("select count(*) from outbox_event where event_type = 'CreditSuspended' "
                    + "and aggregate_id = ?", line.agreementId())).isZero();
            assertThat(e.count("select count(*) from audit_log where action = 'CREDIT_SUSPENDED' and entity_id = ?",
                    line.agreementId())).isZero();
            creditJobs.sweepOverdue(); // and the next sweep agrees
            assertThat(e.agreementRow(line.agreementId()).get("status")).isEqualTo("ACTIVE");
            assertThat(e.dec(e.agreementRow(line.agreementId()), "utilized_amount")).isEqualByComparingTo("5000.00");
            Mockito.reset(invoiceSpy);
        }
    }

    // ── R33 ──────────────────────────────────────────────────────────────

    /** The restaurant's wallet holds {@code amount} that came back from a captured card payment, so it can be withdrawn. */
    private void fundWalletFromCard(CreditWalletSupport.Buyer buyer, String amount) throws Exception {
        var seller = s.newSeller();
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = s.api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "100", "gstRate", "0")).at("/data/id").asLong();
        var orders = new TestOrder(mvc, json, s.api);
        var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(), skuId, 10, 10, "PICKUP", null, null);
        new TestCheckout(paymentProvider, s.api).pay(buyer.token(), placed.paymentId(), placed.providerOrderId());
        for (String step : List.of("preparing", "ready")) {
            var reply = e.call("POST", seller.token(), "/api/v1/supplier-orders/" + placed.orderId() + "/" + step,
                    UUID.randomUUID().toString(), null);
            assertThat(reply.status()).describedAs("supplier " + step + ": " + reply.body()).isEqualTo(200);
        }
        paymentJobs.capturePending();
        refundService.refundToWallet(null, placed.paymentId(), new BigDecimal(amount), RefundReason.DISPUTE_RESOLVED,
                "test", UUID.randomUUID().toString());
    }

    /** Waits (bounded) until {@code count} transactions are blocked on a row lock: the interleaving is then fixed, no sleeps. */
    private void awaitLockWaiters(int count, Future<?>... callers) throws Exception {
        String url;
        try (Connection c = jdbc.getDataSource().getConnection()) {
            url = c.getMetaData().getURL();
        }
        // The application's user may not read performance_schema lock tables; the test container's root may.
        try (Connection root = java.sql.DriverManager.getConnection(url, "root", "mp");
             var st = root.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                try (var rs = st.executeQuery("select count(distinct engine_transaction_id) "
                        + "from performance_schema.data_locks where lock_status = 'WAITING'")) {
                    rs.next();
                    if (rs.getLong(1) >= count) {
                        return;
                    }
                }
                for (var caller : callers) {
                    if (caller.isDone()) {
                        throw new AssertionError("a caller finished instead of waiting: " + caller.get());
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("fewer than " + count + " transactions waiting on a row lock");
    }

    @Test
    @DisplayName("R33 (deterministic): the withdrawal is first in the wallet's lock queue, so the repayment that follows it is refused with the shortfall, not an error")
    void repaymentQueuedBehindAWithdrawalIsRefusedCleanly() throws Exception {
        var buyer = s.newBuyer();
        fundWalletFromCard(buyer, "1000.00");
        var line = s.creditLine(buyer, "200000", Map.of());
        long invoice = s.invoice(line, "100", 10);
        var before = s.snapshot(line);
        var holder = new Holder("select id from wallet where outlet_id = " + buyer.outletId() + " for update");

        Future<Reply> withdraw = pool.submit(() -> e.call("POST", buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/wallet/withdraw", UUID.randomUUID().toString(),
                Map.of("amount", "800.00")));
        awaitLockWaiters(1, withdraw);
        Future<Reply> repay = pool.submit(() -> s.repay(buyer.token(), line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "800.00", "invoiceIds", List.of(invoice))));
        awaitLockWaiters(2, withdraw, repay);
        holder.release();

        var withdrew = withdraw.get(30, TimeUnit.SECONDS);
        var repaid = repay.get(30, TimeUnit.SECONDS);
        assertThat(withdrew.status()).describedAs(withdrew.body().toString()).isEqualTo(200);
        assertThat(repaid.status()).describedAs(repaid.body().toString()).isEqualTo(422);
        assertThat(repaid.code()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
        assertThat(repaid.body().at("/error/details/shortBy").decimalValue()).isEqualByComparingTo("600.00");
        var after = s.snapshot(line);
        assertThat(after.wallet()).isEqualByComparingTo("200.00");
        assertThat(after.repayments()).isEqualTo(before.repayments());
        assertThat(after.creditPayments()).isEqualTo(before.creditPayments());
        assertThat(after.utilized()).isEqualByComparingTo(before.utilized());
        assertThat(after.invoices()).isEqualTo(before.invoices());
    }

    @Test
    @DisplayName("R33: wallet 1000, a withdrawal of 800 and a repayment of 800 at once, three rounds: exactly one succeeds, the other is a clean refusal, balance and ledger agree")
    void withdrawalRacingARepayment() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var buyer = s.newBuyer();
            fundWalletFromCard(buyer, "1000.00");
            var line = s.creditLine(buyer, "200000", Map.of());
            long invoice = s.invoice(line, "100", 10); // 1000 owed
            assertThat(s.balance(buyer)).isEqualByComparingTo("1000.00");

            var start = new CountDownLatch(1);
            Future<Reply> withdraw = pool.submit(() -> {
                start.await();
                return e.call("POST", buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/withdraw",
                        UUID.randomUUID().toString(), Map.of("amount", "800.00"));
            });
            Future<Reply> repay = pool.submit(() -> {
                start.await();
                return s.repay(buyer.token(), line.agreementId(), UUID.randomUUID().toString(),
                        Map.of("amount", "800.00", "invoiceIds", List.of(invoice)));
            });
            start.countDown();
            var withdrew = withdraw.get(30, TimeUnit.SECONDS);
            var repaid = repay.get(30, TimeUnit.SECONDS);

            boolean withdrawalWon = withdrew.status() == 200;
            boolean repaymentWon = repaid.status() == 201;
            assertThat(withdrawalWon ^ repaymentWon)
                    .describedAs("round %d: exactly one wins. withdraw=%d %s repay=%d %s", round, withdrew.status(),
                            withdrew.body(), repaid.status(), repaid.body())
                    .isTrue();
            if (withdrawalWon) {
                assertThat(repaid.status()).describedAs(repaid.body().toString()).isEqualTo(422);
                assertThat(repaid.code()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
            } else {
                assertThat(withdrew.status()).describedAs(withdrew.body().toString()).isIn(400, 422);
            }
            assertThat(s.balance(buyer)).describedAs("round %d", round).isEqualByComparingTo("200.00");
            var ledger = jdbc.queryForObject("select coalesce(sum(case direction when 'CREDIT' then amount "
                    + "else -amount end), 0) from wallet_transaction t join wallet w on w.id = t.wallet_id "
                    + "where w.outlet_id = ?", BigDecimal.class, buyer.outletId());
            assertThat(ledger).describedAs("the ledger sums to the balance").isEqualByComparingTo("200.00");
            assertThat(e.count("select count(*) from credit_repayment where outlet_id = ?", buyer.outletId()))
                    .isEqualTo(repaymentWon ? 1 : 0);
            assertThat(e.count("select count(*) from wallet_transaction t join wallet w on w.id = t.wallet_id "
                    + "where w.outlet_id = ? and t.kind = 'WITHDRAWAL'", buyer.outletId()))
                    .isEqualTo(withdrawalWon ? 1 : 0);
            assertThat(jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?",
                    BigDecimal.class, line.agreementId()))
                    .isEqualByComparingTo(repaymentWon ? "200.00" : "1000.00");
        }
    }
}
