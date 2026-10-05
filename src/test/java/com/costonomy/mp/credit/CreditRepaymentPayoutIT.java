package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Fx;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.settlement.service.SettlementReconciliationService;
import com.costonomy.mp.settlement.service.SettlementService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wallet repayment is paid out to the supplier through the settlement, less commission (D-126). Every test
 * asserts the rows, not only the status.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditRepaymentPayoutIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementService settlementService;
    @Autowired private SettlementReconciliationService reconciliation;

    private CreditWalletSupport s;
    private Instant start;
    private Instant end;
    private final List<Long> deactivated = new ArrayList<>();

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
        // The settlement queries bind java.sql.Timestamp, which renders in the JVM's zone, while MySQL runs in UTC.
        // Production runs in UTC; a developer's JVM may not, so the window is shifted to read as UTC wall clock.
        start = dbWallClock(Instant.now().minus(Duration.ofSeconds(10)));
        end = dbWallClock(Instant.now().plus(Duration.ofDays(1)));
    }

    private static Instant dbWallClock(Instant instant) {
        return instant.minusSeconds(java.time.ZoneId.systemDefault().getRules().getOffset(instant).getTotalSeconds());
    }

    @AfterEach
    void restoreRates() {
        for (Long id : deactivated) {
            jdbc.update("update commission_configuration set status = 'ACTIVE' where id = ?", id);
        }
        deactivated.clear();
    }

    /** One line with two invoices (6500 and 4000), a wallet of 20000 and a 2.5% negotiated rate for its supplier. */
    private Fx fx() throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "65", 100);
        long second = s.invoice(line, "40", 100);
        s.topUp(line.buyer(), "20000.00");
        setStoreRate(line, "2.5");
        return new Fx(line, first, second);
    }

    private long orgOf(Line line) {
        return jdbc.queryForObject("select supplier_organization_id from supplier_store where id = ?", Long.class,
                line.seller().storeId());
    }

    private long setStoreRate(Line line, String rate) {
        jdbc.update("""
                insert into commission_configuration (scope_type, scope_id, rate_percent, config_version, description,
                    effective_from, status, created_at, updated_at, version)
                values ('SUPPLIER', ?, ?, 1, 'D-126 test', now(6) - interval 1 minute, 'ACTIVE', now(6), now(6), 0)
                """, orgOf(line), new BigDecimal(rate));
        return jdbc.queryForObject("select max(id) from commission_configuration", Long.class);
    }

    private CreditWalletSupport.Reply pay(Line line, String amount, Long... invoiceIds) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("amount", amount);
        if (invoiceIds.length > 0) {
            body.put("invoiceIds", List.of(invoiceIds));
        }
        return s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(), body);
    }

    private Map<String, Object> payoutOf(long repaymentId) {
        return jdbc.queryForMap("select * from credit_repayment_payout where credit_repayment_id = ?", repaymentId);
    }

    private long payAndGetRepayment(Line line, String amount, Long... invoiceIds) throws Exception {
        var reply = pay(line, amount, invoiceIds);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        return reply.data().get("repaymentId").asLong();
    }

    private int generate() {
        return settlementService.generate(start, end);
    }

    private Map<String, Object> settlementOf(Line line) {
        return jdbc.queryForMap("select * from settlement where supplier_store_id = ? order by id desc limit 1",
                line.seller().storeId());
    }

    private List<Map<String, Object>> adjustmentsOf(Object settlementId) {
        return jdbc.queryForList("select * from settlement_adjustment where settlement_id = ? order by id",
                settlementId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ── writing the payout ───────────────────────────────────────────────

    @Test
    @DisplayName("a wallet repayment creates one pending payout with the commission snapshotted, rounded half up")
    void walletRepaymentCreatesOnePendingPayoutWithSnapshot() throws Exception {
        var fx = fx();
        long configId = jdbc.queryForObject("select max(id) from commission_configuration", Long.class);

        // 4.20 x 2.5% = 0.105: half up is 0.11 (half even would say 0.10, and no rounding 0.1050).
        long repaymentId = payAndGetRepayment(fx.line(), "4.20", fx.first());

        var payout = payoutOf(repaymentId);
        assertThat((BigDecimal) payout.get("amount")).isEqualByComparingTo("4.20");
        assertThat((BigDecimal) payout.get("commission_rate_percent")).isEqualByComparingTo("2.5");
        assertThat(((Number) payout.get("commission_configuration_id")).longValue()).isEqualTo(configId);
        assertThat((BigDecimal) payout.get("commission_amount")).isEqualByComparingTo("0.11");
        assertThat(payout.get("status")).isEqualTo("PENDING");
        assertThat(payout.get("settlement_id")).isNull();
        assertThat(((Number) payout.get("supplier_store_id")).longValue()).isEqualTo(fx.line().seller().storeId());
        assertThat(count("select count(*) from credit_repayment_payout where credit_repayment_id = ?",
                repaymentId)).isEqualTo(1);

        // The rate is snapshotted: a later change to the configuration does not touch the row.
        jdbc.update("update commission_configuration set rate_percent = 9 where id = ?", configId);
        jdbc.update("""
                insert into commission_configuration (scope_type, scope_id, rate_percent, config_version, description,
                    effective_from, status, created_at, updated_at, version)
                values ('SUPPLIER', ?, 7, 2, 'D-126 test, a newer version', now(6) - interval 1 minute, 'ACTIVE',
                        now(6), now(6), 0)
                """, orgOf(fx.line()));
        var after = payoutOf(repaymentId);
        assertThat((BigDecimal) after.get("commission_rate_percent")).isEqualByComparingTo("2.5");
        assertThat((BigDecimal) after.get("commission_amount")).isEqualByComparingTo("0.11");

        // And the settlement charges the snapshot, not the rate of the day it is generated.
        generate();
        var debit = adjustmentsOf(settlementOf(fx.line()).get("id")).stream()
                .filter(a -> "CREDIT_COMMISSION".equals(a.get("reason_code"))).findFirst().orElseThrow();
        assertThat((BigDecimal) debit.get("amount")).isEqualByComparingTo("0.11");
    }

    @Test
    @DisplayName("no resolvable rate means zero commission")
    void noRateMeansZeroCommission() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        // Nothing resolves: not even the platform default.
        for (Long id : jdbc.queryForList("select id from commission_configuration where status = 'ACTIVE'", Long.class)) {
            deactivated.add(id);
        }
        jdbc.update("update commission_configuration set status = 'INACTIVE' where status = 'ACTIVE'");

        long repaymentId = payAndGetRepayment(line, "1000.00", invoice);

        var payout = payoutOf(repaymentId);
        assertThat(payout.get("commission_rate_percent")).isNull();
        assertThat(payout.get("commission_configuration_id")).isNull();
        assertThat((BigDecimal) payout.get("commission_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) payout.get("amount")).isEqualByComparingTo("1000.00");
        assertThat(payout.get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("an overpayment leaves no payout")
    void failedRepaymentLeavesNoPayout() throws Exception {
        var fx = fx();

        var over = pay(fx.line(), "10500.01");

        assertThat(over.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(count("select count(*) from credit_repayment_payout where supplier_store_id = ?",
                fx.line().seller().storeId())).isZero();
        assertThat(count("select count(*) from credit_repayment where supplier_store_id = ?",
                fx.line().seller().storeId())).isZero();
    }

    @Test
    @DisplayName("an insufficient wallet leaves no payout")
    void insufficientWalletLeavesNoPayout() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "100.00");
        long before = count("select count(*) from credit_repayment_payout where supplier_store_id = ?",
                line.seller().storeId());

        var reply = pay(line, "500.00", invoice);

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
        assertThat(count("select count(*) from credit_repayment_payout where supplier_store_id = ?",
                line.seller().storeId())).isEqualTo(before);
        assertThat(count("select count(*) from credit_repayment where supplier_store_id = ?",
                line.seller().storeId())).isZero();
    }

    // ── applying it ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a settlement applies the payout once: a credit, a commission debit, ids on the row, the right net")
    void settlementAppliesPayoutOnce() throws Exception {
        var fx = fx();
        long repaymentId = payAndGetRepayment(fx.line(), "1000.00", fx.first());

        generate();
        generate();

        var settlement = settlementOf(fx.line());
        var adjustments = adjustmentsOf(settlement.get("id"));
        assertThat(adjustments).hasSize(2);
        var credit = adjustments.get(0);
        var debit = adjustments.get(1);
        assertThat(credit.get("direction")).isEqualTo("CREDIT");
        assertThat(credit.get("reason_code")).isEqualTo("CREDIT_REPAYMENT");
        assertThat((BigDecimal) credit.get("amount")).isEqualByComparingTo("1000.00");
        assertThat(debit.get("direction")).isEqualTo("DEBIT");
        assertThat(debit.get("reason_code")).isEqualTo("CREDIT_COMMISSION");
        assertThat((BigDecimal) debit.get("amount")).isEqualByComparingTo("25.00");

        var payout = payoutOf(repaymentId);
        assertThat(payout.get("status")).isEqualTo("APPLIED");
        assertThat(payout.get("settlement_id")).isEqualTo(settlement.get("id"));
        assertThat(payout.get("credit_adjustment_id")).isEqualTo(credit.get("id"));
        assertThat(payout.get("commission_adjustment_id")).isEqualTo(debit.get("id"));
        assertThat(payout.get("applied_at")).isNotNull();

        assertThat((BigDecimal) settlement.get("net_amount")).isEqualByComparingTo("975.00");
        assertThat((BigDecimal) settlement.get("adjustment_amount")).isEqualByComparingTo("975.00");
        assertThat((BigDecimal) settlement.get("gross_amount")).isEqualByComparingTo("0");
        assertThat(settlement.get("status")).isEqualTo("CALCULATED");
    }

    @Test
    @DisplayName("a store whose only activity is wallet repayments still gets a settlement")
    void storeWithOnlyCreditRepaymentsGetsASettlement() throws Exception {
        var fx = fx();
        payAndGetRepayment(fx.line(), "400.00", fx.first());
        assertThat(count("select count(*) from settlement where supplier_store_id = ?",
                fx.line().seller().storeId())).isZero();

        int touched = generate();

        assertThat(touched).isGreaterThanOrEqualTo(1);
        var settlement = settlementOf(fx.line());
        assertThat((BigDecimal) settlement.get("net_amount")).isEqualByComparingTo("390.00");
        assertThat(((Number) settlement.get("order_count")).intValue()).isZero();
        assertThat(((Number) settlement.get("supplier_organization_id")).longValue()).isEqualTo(orgOf(fx.line()));
    }

    @Test
    @DisplayName("an approved settlement never receives a payout; the next settlement does")
    void approvedSettlementDefersPayoutToNext() throws Exception {
        var fx = fx();
        payAndGetRepayment(fx.line(), "1000.00", fx.first());
        generate();
        var first = settlementOf(fx.line());
        jdbc.update("update settlement set status = 'APPROVED', approved_at = now(6) where id = ?", first.get("id"));
        long secondRepayment = payAndGetRepayment(fx.line(), "2000.00", fx.second());

        generate(); // the same period: its settlement is approved

        var payout = payoutOf(secondRepayment);
        assertThat(payout.get("status")).isEqualTo("PENDING");
        assertThat(payout.get("settlement_id")).isNull();
        assertThat(adjustmentsOf(first.get("id"))).hasSize(2);
        var unchanged = jdbc.queryForMap("select * from settlement where id = ?", first.get("id"));
        assertThat((BigDecimal) unchanged.get("net_amount")).isEqualByComparingTo("975.00");

        // The next period picks it up.
        settlementService.generate(start, end.plus(Duration.ofDays(1)));

        var applied = payoutOf(secondRepayment);
        assertThat(applied.get("status")).isEqualTo("APPLIED");
        assertThat(applied.get("settlement_id")).isNotEqualTo(first.get("id"));
        var next = jdbc.queryForMap("select * from settlement where id = ?", applied.get("settlement_id"));
        assertThat((BigDecimal) next.get("net_amount")).isEqualByComparingTo("1950.00");
        assertThat(adjustmentsOf(next.get("id"))).hasSize(2);
    }

    @Test
    @DisplayName("prepaid orders and repayments share one settlement: net = orders net + repayment - commission")
    void prepaidOrdersAndRepaymentsInTheSameSettlement() throws Exception {
        var fx = fx();
        // The second invoice's order, made a settled-ready prepaid one: 4000 at 2.5% is 100 commission.
        long orderId = jdbc.queryForObject("select supplier_order_id from credit_invoice where id = ?", Long.class,
                fx.second());
        jdbc.update("update supplier_order set payment_method = 'PREPAID', status = 'COMPLETED', "
                + "accepted_amount = 4000, delivery_fee = 0 where id = ?", orderId);
        payAndGetRepayment(fx.line(), "1000.00", fx.first());

        generate();

        var settlement = settlementOf(fx.line());
        assertThat((BigDecimal) settlement.get("gross_amount")).isEqualByComparingTo("4000.00");
        assertThat((BigDecimal) settlement.get("commission_amount")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) settlement.get("adjustment_amount")).isEqualByComparingTo("975.00");
        assertThat((BigDecimal) settlement.get("net_amount")).isEqualByComparingTo("4875.00");
        assertThat(((Number) settlement.get("order_count")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("a completed credit order is still never settled, even beside a repayment (D-117)")
    void creditOrdersStillNeverSettled() throws Exception {
        var fx = fx();
        long orderId = jdbc.queryForObject("select supplier_order_id from credit_invoice where id = ?", Long.class,
                fx.second());
        jdbc.update("update supplier_order set status = 'COMPLETED', accepted_amount = 4000 where id = ?", orderId);
        assertThat(jdbc.queryForObject("select payment_method from supplier_order where id = ?", String.class,
                orderId)).isEqualTo("CREDIT");
        payAndGetRepayment(fx.line(), "1000.00", fx.first());

        generate();

        assertThat(count("select count(*) from commission_calculation where supplier_order_id = ?", orderId)).isZero();
        var settlement = settlementOf(fx.line());
        assertThat((BigDecimal) settlement.get("gross_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) settlement.get("net_amount")).isEqualByComparingTo("975.00");
    }

    @Test
    @DisplayName("two generations at once apply the payout once")
    void concurrentGenerateAppliesOnce() throws Exception {
        var fx = fx();
        long repaymentId = payAndGetRepayment(fx.line(), "1000.00", fx.first());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            List<Future<Boolean>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    gate.await();
                    try {
                        generate();
                        return true;
                    } catch (RuntimeException ex) {
                        // The loser may be refused (one settlement per store per period); it must not double-apply.
                        return false;
                    }
                }));
            }
            gate.countDown();
            boolean any = false;
            for (var run : runs) {
                any |= run.get(60, TimeUnit.SECONDS);
            }
            assertThat(any).isTrue();
        } finally {
            pool.shutdownNow();
        }
        generate();

        var settlement = settlementOf(fx.line());
        assertThat(count("select count(*) from settlement where supplier_store_id = ?", fx.line().seller().storeId()))
                .isEqualTo(1);
        assertThat(adjustmentsOf(settlement.get("id"))).hasSize(2);
        assertThat(payoutOf(repaymentId).get("status")).isEqualTo("APPLIED");
        assertThat((BigDecimal) settlement.get("net_amount")).isEqualByComparingTo("975.00");
    }

    // ── reconciliation ───────────────────────────────────────────────────

    @Test
    @DisplayName("reconciliation is clean when wallet repayments equal the payouts owed")
    void reconciliationOkWhenEqual() throws Exception {
        var fx = fx();
        payAndGetRepayment(fx.line(), "1000.00", fx.first());
        generate();

        var result = reconciliation.reconcileInternal(((Number) settlementOf(fx.line()).get("id")).longValue(), null);

        assertThat(result.creditRepaymentsMatched()).describedAs(result.note()).isTrue();
        assertThat(result.repaymentWalletDebits()).isEqualByComparingTo(result.repaymentPayoutsOwed());
        assertThat(result.repaymentWalletDebits().signum()).isPositive();
    }

    @Test
    @DisplayName("a difference between wallet repayments and payouts is recorded, never refused")
    void reconciliationFlagsMismatch() throws Exception {
        var fx = fx();
        long repaymentId = payAndGetRepayment(fx.line(), "1000.00", fx.first());
        generate();
        long settlementId = ((Number) settlementOf(fx.line()).get("id")).longValue();
        // 1000 became 900 at the payout: the wallet gave 1000, the supplier is owed 900.
        jdbc.update("update credit_repayment_payout set amount = 900 where credit_repayment_id = ?", repaymentId);
        try {
            var result = reconciliation.reconcileInternal(settlementId, null);

            assertThat(result.creditRepaymentsMatched()).isFalse();
            assertThat(result.note()).contains("Wallet credit repayments");
            var row = jdbc.queryForMap("select reconciled_at, reconciliation_note from settlement where id = ?",
                    settlementId);
            assertThat(row.get("reconciled_at")).isNotNull();
            assertThat((String) row.get("reconciliation_note")).contains("do not match");
            assertThat(count("select count(*) from audit_log where action = "
                    + "'SETTLEMENT_REPAYMENT_RECONCILIATION_MISMATCH' and entity_id = ?", settlementId))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            jdbc.update("update credit_repayment_payout set amount = 1000 where credit_repayment_id = ?", repaymentId);
        }
    }
}
