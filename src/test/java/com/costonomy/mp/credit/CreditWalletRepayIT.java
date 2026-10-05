package com.costonomy.mp.credit;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.credit.CreditWalletSupport.Fx;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.Connection;
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
 * A restaurant repays a credit invoice from its wallet (D-123). Every test asserts the side effects, not only the
 * status: what moved, and, for a refusal, that nothing did.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditWalletRepayIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private NotificationRelayAccess relay;
    @Autowired private com.costonomy.mp.common.idempotency.IdempotencyService idempotency;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.costonomy.mp.credit.service.CreditRepaymentPayoutWriter payoutWriter;

    private CreditWalletSupport s;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(payoutWriter);
        pool.shutdownNow();
    }

    /** A line with two invoices (6500 and 4000, so 10500 owed) and a wallet holding {@code wallet}. */
    private Fx fx(String wallet) throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "65", 100);
        long second = s.invoice(line, "40", 100);
        s.topUp(line.buyer(), wallet);
        return new Fx(line, first, second);
    }

    private Reply pay(Line line, String amount, Long... invoiceIds) throws Exception {
        return pay(line, UUID.randomUUID().toString(), amount, invoiceIds);
    }

    private Reply pay(Line line, String key, String amount, Long... invoiceIds) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("amount", amount);
        if (invoiceIds.length > 0) {
            body.put("invoiceIds", List.of(invoiceIds));
        }
        return s.repay(line.buyer().token(), line.agreementId(), key, body);
    }

    private String invoiceStatus(long id) {
        return jdbc.queryForObject("select status from credit_invoice where id = ?", String.class, id);
    }

    private BigDecimal paid(long id) {
        return jdbc.queryForObject("select paid_amount from credit_invoice where id = ?", BigDecimal.class, id);
    }

    private BigDecimal utilized(Line line) {
        return jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?", BigDecimal.class,
                line.agreementId());
    }

    private int events(String type, long agreementId) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = ? "
                + "and json_extract(payload, '$.creditAgreementId') = ?", Integer.class, type, agreementId);
    }

    private List<Map<String, Object>> walletRows(Line line, String kind) {
        return jdbc.queryForList("select t.* from wallet_transaction t join wallet w on w.id = t.wallet_id "
                + "where w.outlet_id = ? and t.kind = ?", line.buyer().outletId(), kind);
    }

    private void assertNothingMoved(Line line, CreditWalletSupport.Snapshot before) {
        var after = s.snapshot(line);
        assertThat(after.wallet()).isEqualByComparingTo(before.wallet());
        assertThat(after.walletRows()).isEqualTo(before.walletRows());
        assertThat(after.repayments()).isEqualTo(before.repayments());
        assertThat(after.creditPayments()).isEqualTo(before.creditPayments());
        assertThat(after.utilized()).isEqualByComparingTo(before.utilized());
        assertThat(after.invoices()).isEqualTo(before.invoices());
        assertThat(after.ledgerRows()).isEqualTo(before.ledgerRows());
        assertThat(after.outbox()).isEqualTo(before.outbox());
    }

    // ── the happy paths ──────────────────────────────────────────────────

    @Test
    @DisplayName("paying one invoice in full settles it, takes the money once, and tells the supplier, not the restaurant")
    void paysOneInvoiceInFull() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        String key = UUID.randomUUID().toString();

        var reply = pay(line, key, "6500.00", fx.first());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        var d = reply.data();
        long repaymentId = d.get("repaymentId").asLong();
        assertThat(d.get("amount").decimalValue()).isEqualByComparingTo("6500.00");
        assertThat(d.get("walletBalanceAfter").decimalValue()).isEqualByComparingTo("13500.00");
        assertThat(d.at("/allocations")).hasSize(1);
        assertThat(d.at("/allocations/0/invoiceId").asLong()).isEqualTo(fx.first());
        assertThat(d.at("/allocations/0/amount").decimalValue()).isEqualByComparingTo("6500.00");
        assertThat(d.at("/allocations/0/statusAfter").asText()).isEqualTo("PAID");
        assertThat(d.at("/allocations/0/invoiceNumber").asText()).isNotBlank();
        assertThat(d.at("/agreement/due").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(d.at("/agreement/overdue").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.at("/agreement/available").decimalValue()).isEqualByComparingTo("196000.00");
        assertThat(d.at("/agreement/status").asText()).isEqualTo("ACTIVE");

        assertThat(invoiceStatus(fx.first())).isEqualTo("PAID");
        assertThat(invoiceStatus(fx.second())).isEqualTo("ISSUED");
        assertThat(utilized(line)).isEqualByComparingTo("4000.00");
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("13500.00");

        var rows = walletRows(line, "CREDIT_REPAYMENT");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("reference")).isEqualTo("credit-repayment-" + repaymentId);
        assertThat(rows.get(0).get("direction")).isEqualTo("DEBIT");
        assertThat(d.get("walletEntryId").asLong()).isEqualTo(((Number) rows.get(0).get("id")).longValue());
        assertThat(rows.get(0).get("reason")).isEqualTo("Credit repayment to ABC store");

        var repayment = jdbc.queryForMap("select * from credit_repayment where id = ?", repaymentId);
        assertThat(repayment.get("wallet_transaction_id")).isEqualTo(rows.get(0).get("id"));
        assertThat(repayment.get("source")).isEqualTo("WALLET");
        assertThat(repayment.get("status")).isEqualTo("COMPLETED");
        assertThat(repayment.get("idempotency_key")).isEqualTo("wallet:" + line.buyer().outletId() + ":" + key);
        assertThat((BigDecimal) repayment.get("amount")).isEqualByComparingTo("6500.00");
        assertThat(repayment.get("credit_agreement_id")).isEqualTo(line.agreementId());
        assertThat(repayment.get("supplier_store_id")).isEqualTo(line.seller().storeId());

        var payments = jdbc.queryForList("select * from credit_payment where credit_repayment_id = ?", repaymentId);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("source")).isEqualTo("WALLET");
        assertThat(payments.get(0).get("method")).isEqualTo("WALLET");
        assertThat(payments.get(0).get("reference")).isEqualTo("credit-repayment-" + repaymentId);
        assertThat(payments.get(0).get("credit_invoice_id")).isEqualTo(fx.first());

        // The supplier is told, once; the restaurant is not sent "the supplier recorded your payment".
        assertThat(events("CreditRepaymentReceived", line.agreementId())).isEqualTo(1);
        assertThat(events("CreditRepaymentRecorded", line.agreementId())).isZero();
        relayEvents("CreditRepaymentReceived", line.agreementId());
        var told = jdbc.queryForList("select title, body, audience, critical from notification "
                + "where event_type = 'CreditRepaymentReceived' and target_id = ?", line.agreementId());
        assertThat(told).hasSize(1);
        assertThat(told.get(0).get("audience")).isEqualTo("SUPPLIER_STORE");
        assertThat((String) told.get(0).get("title")).isEqualTo("Payment received");
        assertThat((String) told.get(0).get("body")).contains("Paradise").contains("6,500");
        assertThat(jdbc.queryForObject("select count(*) from notification where event_type like 'CreditRepayment%' "
                + "and audience = 'OUTLET' and target_id = ?", Integer.class, line.agreementId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'CREDIT_REPAYMENT_FROM_WALLET' "
                + "and entity_id = ?", Integer.class, repaymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a part payment on an overdue invoice leaves it overdue, with no second CreditOverdue")
    void partialStaysOverdue() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(invoiceStatus(invoice)).isEqualTo("OVERDUE");
        assertThat(events("CreditOverdue", line.agreementId())).isEqualTo(1);

        var reply = pay(line, "1000.00");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(invoiceStatus(invoice)).isEqualTo("OVERDUE");
        assertThat(reply.data().at("/allocations/0/statusAfter").asText()).isEqualTo("OVERDUE");
        assertThat(paid(invoice)).isEqualByComparingTo("1000.00");
        assertThat(reply.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("5500.00");
        creditJobs.sweepOverdue();
        assertThat(events("CreditOverdue", line.agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("without invoice ids the oldest due date is paid first, then the next; the response lists them in that order")
    void allocatesOldestFirst() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        // The second invoice was raised later but is made the older debt: the due date decides, not the id.
        jdbc.update("update credit_invoice set due_date = date_sub(curdate(), interval 3 day) where id = ?", fx.second());

        var reply = pay(line, "5000.00");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        var allocations = reply.data().get("allocations");
        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0).get("invoiceId").asLong()).isEqualTo(fx.second());
        assertThat(allocations.get(0).get("amount").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(allocations.get(0).get("statusAfter").asText()).isEqualTo("PAID");
        assertThat(allocations.get(1).get("invoiceId").asLong()).isEqualTo(fx.first());
        assertThat(allocations.get(1).get("amount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(allocations.get(1).get("statusAfter").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(invoiceStatus(fx.second())).isEqualTo("PAID");
        assertThat(paid(fx.first())).isEqualByComparingTo("1000.00");
        assertThat(utilized(line)).isEqualByComparingTo("5500.00");
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("15000.00");
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_payment where credit_repayment_id = ?",
                Integer.class, reply.data().get("repaymentId").asLong())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from credit_transaction where credit_agreement_id = ? "
                + "and transaction_type = 'REPAYMENT'", Integer.class, line.agreementId())).isEqualTo(2);
        assertThat(events("CreditRepaymentReceived", line.agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("with invoice ids only those are paid; another agreement's or a settled invoice is not found, and nothing moves")
    void specificInvoiceIds() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var stranger = s.creditLine("100000");
        long foreign = s.invoice(stranger, "10", 100);

        var ok = pay(line, "1000.00", fx.second());
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(201);
        assertThat(paid(fx.second())).isEqualByComparingTo("1000.00");
        assertThat(paid(fx.first())).isEqualByComparingTo("0");

        var before = s.snapshot(line);
        var strangerBefore = s.snapshot(stranger);
        var refused = pay(line, "100.00", foreign);
        var missing = pay(line, "100.00", 999_999_999L);
        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("RESOURCE_NOT_FOUND");
        // Another tenant's invoice looks exactly like one that does not exist.
        assertThat(refused.body().at("/error/message").asText().replace(String.valueOf(foreign), "N"))
                .isEqualTo(missing.body().at("/error/message").asText().replace("999999999", "N"));
        assertThat(refused.body().at("/error/details")).isEqualTo(missing.body().at("/error/details"));
        // One of the ids is foreign: the whole request is refused, not the part that is ours.
        assertThat(pay(line, "100.00", fx.first(), foreign).status()).isEqualTo(404);
        assertNothingMoved(line, before);
        assertThat(s.snapshot(stranger).invoices()).isEqualTo(strangerBefore.invoices());
        assertThat(s.snapshot(stranger).wallet()).isEqualByComparingTo(strangerBefore.wallet());
    }

    // ── refusals: nothing moves ──────────────────────────────────────────

    @Test
    @DisplayName("paying more than is owed is CREDIT_OVERPAYMENT with the outstanding total, and nothing moves")
    void overpaymentRefusedNothingMoves() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var before = s.snapshot(line);

        var all = pay(line, "10500.01");
        assertThat(all.status()).isEqualTo(422);
        assertThat(all.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(all.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("10500.00");

        var one = pay(line, "6500.01", fx.first());
        assertThat(one.status()).isEqualTo(422);
        assertThat(one.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(one.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("6500.00");

        assertNothingMoved(line, before);
    }

    @Test
    @DisplayName("a wallet that cannot cover it is WALLET_INSUFFICIENT_BALANCE with shortBy, and nothing moves")
    void insufficientRefusedNothingMoves() throws Exception {
        var fx = fx("3000.00");
        var line = fx.line();
        var before = s.snapshot(line);

        var reply = pay(line, "5000.00");

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
        assertThat(reply.body().at("/error/details/shortBy").decimalValue()).isEqualByComparingTo("2000.00");
        assertNothingMoved(line, before);
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).isEmpty();
    }

    @Test
    @DisplayName("a wallet on hold is refused and nothing moves")
    void walletOnHoldRefused() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        jdbc.update("update wallet set status = 'ON_HOLD' where outlet_id = ?", line.buyer().outletId());
        var before = s.snapshot(line);

        var reply = pay(line, "1000.00");

        assertThat(reply.status()).isEqualTo(403);
        // D-129: its own code, so the app can say "contact support" instead of "not available yet" (the flag-off
        // refusal stays FORBIDDEN: CreditWalletRepayDisabledIT).
        assertThat(reply.code()).isEqualTo("WALLET_ON_HOLD");
        assertThat(reply.body().at("/error/message").asText()).isEqualTo("Your wallet is on hold. Please contact support.");
        assertNothingMoved(line, before);
    }

    @Test
    @DisplayName("a user with no access to the outlet gets 404 and nothing moves")
    void otherOutletUserGets404() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var before = s.snapshot(line);
        var other = s.newBuyer();

        var reply = s.repay(other.token(), line.agreementId(), UUID.randomUUID().toString(), Map.of("amount", "100.00"));

        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertNothingMoved(line, before);
    }

    @Test
    @DisplayName("the supplier cannot repay on the restaurant's behalf")
    void supplierUserRefused() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var before = s.snapshot(line);

        var reply = s.repay(line.seller().token(), line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "100.00"));

        assertThat(reply.status()).isEqualTo(404);
        assertNothingMoved(line, before);
    }

    @Test
    @DisplayName("an amount with more than two decimals is a validation error, and nothing moves")
    void amountMoreThanTwoDecimalsRejected() throws Exception {
        var fx = fx("20000.00");
        var before = s.snapshot(fx.line());

        var reply = pay(fx.line(), "10.001");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(reply.body().at("/error/details/fields/amount").asText()).isNotBlank();
        assertNothingMoved(fx.line(), before);
    }

    @Test
    @DisplayName("an amount below one rupee is a validation error, and nothing moves")
    void amountBelowOneRejected() throws Exception {
        var fx = fx("20000.00");
        var before = s.snapshot(fx.line());

        for (String amount : List.of("0.99", "0.00", "-5.00")) {
            var reply = pay(fx.line(), amount);
            assertThat(reply.status()).as(amount).isEqualTo(400);
            assertThat(reply.code()).as(amount).isEqualTo("VALIDATION_ERROR");
        }
        assertNothingMoved(fx.line(), before);
    }

    @Test
    @DisplayName("a missing amount, a missing key, an empty or repeated id list are refused")
    void badBodiesRefused() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var before = s.snapshot(line);
        String token = line.buyer().token();
        String key = UUID.randomUUID().toString();

        assertThat(s.repay(token, line.agreementId(), key, Map.of()).status()).isEqualTo(400);
        assertThat(s.repay(token, line.agreementId(), null, Map.of("amount", "10.00")).status()).isEqualTo(400);
        assertThat(s.repay(token, line.agreementId(), key, Map.of("amount", "10.00", "invoiceIds", List.of()))
                .status()).isEqualTo(400);
        assertThat(pay(line, "10.00", fx.first(), fx.first()).status()).isEqualTo(400);
        assertThat(s.repay(token, line.agreementId(), "k".repeat(101), Map.of("amount", "10.00")).status())
                .isEqualTo(400);
        assertNothingMoved(line, before);
    }

    // ── idempotency ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the same key and payload twice gives the same response, one debit and one repayment")
    void sameKeyTwiceOneDebit() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        String key = UUID.randomUUID().toString();

        var first = pay(line, key, "2500.00");
        var second = pay(line, key, "2500.00");

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.data()).isEqualTo(first.data());
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("17500.00");
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_repayment where outlet_id = ?", Integer.class,
                line.buyer().outletId())).isEqualTo(1);
        assertThat(utilized(line)).isEqualByComparingTo("8000.00");
        assertThat(events("CreditRepaymentReceived", line.agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different amount is refused and takes nothing")
    void sameKeyDifferentAmountRejected() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        String key = UUID.randomUUID().toString();
        assertThat(pay(line, key, "2500.00").status()).isEqualTo(201);

        var again = pay(line, key, "500.00");

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("17500.00");
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).hasSize(1);
    }

    @Test
    @DisplayName("two logically equal payloads built in different map orders are ONE request for the service (B1)")
    void equalPayloadsInAnyMapOrderReplay() throws Exception {
        var buyer = s.newBuyer();
        var first = new java.util.LinkedHashMap<String, Object>();
        first.put("agreementId", 7L);
        first.put("amount", "1000.00");
        first.put("invoiceIds", List.of(3L, 9L));
        var second = new java.util.LinkedHashMap<String, Object>();
        second.put("invoiceIds", List.of(3L, 9L));
        second.put("amount", "1000.00");
        second.put("agreementId", 7L);
        var runs = new java.util.concurrent.atomic.AtomicInteger();
        String key = UUID.randomUUID().toString();

        String one = idempotency.execute(buyer.outletId(), "credit.test-order", key, first, String.class,
                () -> "run-" + runs.incrementAndGet());
        String two = idempotency.execute(buyer.outletId(), "credit.test-order", key, second, String.class,
                () -> "run-" + runs.incrementAndGet());

        assertThat(runs.get()).describedAs("the retry must replay, not run again").isEqualTo(1);
        assertThat(two).isEqualTo(one);
    }

    @Test
    @DisplayName("a failed attempt: the same key is answered IDEMPOTENT_PREVIOUS_ATTEMPT_FAILED, a NEW key succeeds, one debit in all (B3)")
    void failedAttemptNeedsANewKey() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        String key = UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new IllegalStateException("payout writer down"))
                .doCallRealMethod().when(payoutWriter).record(org.mockito.ArgumentMatchers.any());

        var failed = pay(line, key, "2500.00");
        var again = pay(line, key, "2500.00");
        var before = s.snapshot(line);
        var fresh = pay(line, UUID.randomUUID().toString(), "2500.00");

        assertThat(failed.status()).isGreaterThanOrEqualTo(500);
        assertThat(before.wallet()).describedAs("the failed attempt rolled back").isEqualByComparingTo("20000.00");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("IDEMPOTENT_PREVIOUS_ATTEMPT_FAILED");
        assertThat(again.body().at("/error/message").asText())
                .isEqualTo("The previous attempt did not go through. Please try again.");
        assertThat(fresh.status()).describedAs(fresh.body().toString()).isEqualTo(201);
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("17500.00");
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_repayment where outlet_id = ?", Integer.class,
                line.buyer().outletId())).isEqualTo(1);
    }

    // ── number formats and the edges of the balance ──────────────────────

    @Test
    @DisplayName("1000, 1000.0, '1000.00' and '1e3' with one key are one debit and replays; 'NaN' is refused (M07)")
    void numberFormatsAreOneRequest() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        String key = UUID.randomUUID().toString();
        Object[] forms = {1000, 1000.0, "1000.00", "1e3", "1000"};

        Reply first = null;
        for (Object form : forms) {
            var reply = s.repay(line.buyer().token(), line.agreementId(), key, Map.of("amount", form));
            if (first == null) {
                first = reply;
                assertThat(reply.status()).describedAs(String.valueOf(form) + " " + reply.body()).isEqualTo(201);
            }
            assertThat(reply.status()).describedAs(String.valueOf(form)).isEqualTo(201);
            assertThat(reply.data()).describedAs(String.valueOf(form)).isEqualTo(first.data());
        }
        var nan = s.repay(line.buyer().token(), line.agreementId(), key, Map.of("amount", "NaN"));

        assertThat(nan.status()).isEqualTo(400);
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("19000.00");
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_repayment where outlet_id = ?", Integer.class,
                line.buyer().outletId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a wallet holding exactly the amount: 201 and a balance of 0.00 (M27)")
    void exactBalanceSucceeds() throws Exception {
        var fx = fx("1000.00");
        var line = fx.line();

        var reply = pay(line, "1000.00");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("0.00");
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).hasSize(1);
    }

    @Test
    @DisplayName("a wallet one paisa short: 422 WALLET_INSUFFICIENT_BALANCE, shortBy 0.01, nothing moves (M28)")
    void onePaisaShortRefused() throws Exception {
        var fx = fx("999.99");
        var line = fx.line();
        var before = s.snapshot(line);

        var reply = pay(line, "1000.00");

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
        assertThat(reply.body().at("/error/details/shortBy").decimalValue()).isEqualByComparingTo("0.01");
        assertNothingMoved(line, before);
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("999.99");
    }

    // ── concurrency and locks ────────────────────────────────────────────

    @Test
    @DisplayName("two repayments each for the full outstanding of the same invoice: one succeeds, the other is an overpayment")
    void concurrentRepaymentsNeverOverpay() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");

        var start = new CountDownLatch(1);
        List<Future<Reply>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return pay(line, "6500.00");
            }));
        }
        start.countDown();
        List<Reply> replies = new ArrayList<>();
        for (var call : calls) {
            replies.add(call.get(60, TimeUnit.SECONDS));
        }

        assertThat(replies.stream().map(Reply::status)).containsExactlyInAnyOrder(201, 422);
        var refused = replies.stream().filter(r -> r.status() == 422).findFirst().orElseThrow();
        assertThat(refused.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(refused.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("13500.00");
        assertThat(walletRows(line, "CREDIT_REPAYMENT")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_repayment where outlet_id = ?", Integer.class,
                line.buyer().outletId())).isEqualTo(1);
        assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
        assertThat(paid(invoice)).isEqualByComparingTo("6500.00");
        assertThat(utilized(line)).isEqualByComparingTo("0");
    }

    /** Holds a connection with an open transaction until {@link #release()}; runs {@code sqls} after taking it. */
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

    @Test
    @DisplayName("the wallet is locked before any invoice: while the wallet is held, the repayment has not touched the invoices")
    void lockOrderIsWalletThenInvoices() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var holder = new Holder("select id from wallet where outlet_id = " + line.buyer().outletId() + " for update");

        var call = pool.submit(() -> pay(line, "1000.00"));
        Thread.sleep(1500);
        assertThat(call.isDone()).describedAs("the repayment must wait for the wallet").isFalse();
        // If the repayment had locked the invoices first, this would fail at once.
        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("select id from credit_invoice where credit_agreement_id = " + line.agreementId()
                        + " for update nowait");
            }
            c.rollback();
        }
        holder.release();
        assertThat(call.get(30, TimeUnit.SECONDS).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("the invoices are locked FOR UPDATE: a change committed while the repayment waited is what it allocates against")
    void invoiceRowsAreLockedForUpdate() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        // Someone else holds invoice 1, and settles 3000 of it (and of the exposure) before letting go.
        var holder = new Holder(
                "select id from credit_invoice where id = " + fx.first() + " for update",
                "update credit_invoice set paid_amount = 3000, status = 'PARTIALLY_PAID', version = version + 1 "
                        + "where id = " + fx.first(),
                "update credit_agreement set utilized_amount = utilized_amount - 3000 where id = " + line.agreementId());

        var call = pool.submit(() -> pay(line, "7500.00"));
        Thread.sleep(1500);
        assertThat(call.isDone()).describedAs("the repayment must wait for the invoice").isFalse();
        holder.release();

        var reply = call.get(30, TimeUnit.SECONDS);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        // 3500 left on invoice 1 and 4000 on invoice 2 is exactly 7500: against the stale 6500 it would have
        // allocated 6500 + 1000 and then failed on the changed row.
        var allocations = reply.data().get("allocations");
        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0).get("amount").decimalValue()).isEqualByComparingTo("3500.00");
        assertThat(invoiceStatus(fx.first())).isEqualTo("PAID");
        assertThat(invoiceStatus(fx.second())).isEqualTo("PAID");
        assertThat(utilized(line)).isEqualByComparingTo("0");
    }

    // ── credit status ────────────────────────────────────────────────────

    private Line lineWithMaxOverdue(String max) throws Exception {
        return s.creditLine("200000", Map.of("maxOverdueAmount", max));
    }

    @Test
    @DisplayName("repaying on a system-suspended line is allowed and reinstates it once the overdue is within the maximum")
    void repayOnSuspendedLineAllowedAndReinstatesSystemSuspension() throws Exception {
        var line = lineWithMaxOverdue("10000");
        long invoice = s.invoice(line, "400", 100);
        s.topUp(line.buyer(), "40000.00");
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(status(line)).isEqualTo("SUSPENDED");

        // 40000 overdue, pay 20000: 20000 is still above 10000.
        var first = pay(line, "20000.00");
        assertThat(first.status()).describedAs(first.body().toString()).isEqualTo(201);
        assertThat(first.data().at("/agreement/status").asText()).isEqualTo("SUSPENDED");
        assertThat(status(line)).isEqualTo("SUSPENDED");
        assertThat(events("CreditReinstated", line.agreementId())).isZero();

        var second = pay(line, "10000.00");
        assertThat(second.status()).describedAs(second.body().toString()).isEqualTo(201);
        assertThat(second.data().at("/agreement/status").asText()).isEqualTo("ACTIVE");
        assertThat(status(line)).isEqualTo("ACTIVE");
        assertThat(events("CreditReinstated", line.agreementId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select suspension_source from credit_agreement where id = ?", String.class,
                line.agreementId())).isNull();
        assertThat(utilized(line)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("a supplier's own suspension is never lifted by a repayment")
    void supplierSuspensionNotLiftedByRepayment() throws Exception {
        var line = lineWithMaxOverdue("10000");
        long invoice = s.invoice(line, "400", 100);
        s.topUp(line.buyer(), "40000.00");
        s.age(invoice, 10);
        jdbc.update("update credit_invoice set status = 'OVERDUE' where id = ?", invoice);
        s.api.post(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/suspend",
                Map.of("reason", "Account under review"));

        var reply = pay(line, "40000.00");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(status(line)).isEqualTo("SUSPENDED");
        assertThat(reply.data().at("/agreement/status").asText()).isEqualTo("SUSPENDED");
        assertThat(events("CreditReinstated", line.agreementId())).isZero();
        assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("repayment is allowed whatever the status of the line")
    void repayAllowedOnAnyStatus() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        for (String status : List.of("SUSPENDED", "EXPIRED", "CLOSED", "ACTIVE")) {
            jdbc.update("update credit_agreement set status = ? where id = ?", status, line.agreementId());
            var reply = pay(line, "100.00");
            assertThat(reply.status()).as(status).isEqualTo(201);
            assertThat(reply.data().at("/agreement/status").asText()).as(status).isEqualTo(status);
        }
        assertThat(utilized(line)).isEqualByComparingTo("10100.00");
    }

    private String status(Line line) {
        return jdbc.queryForObject("select status from credit_agreement where id = ?", String.class, line.agreementId());
    }

    // ── what the restaurant sees afterwards ──────────────────────────────

    @Test
    @DisplayName("History lists it as a debit with no bill, and the detail page names the supplier and the invoice numbers")
    void walletHistoryAndDetailShowIt() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var reply = pay(line, "10500.00");
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        long entry = reply.data().get("walletEntryId").asLong();
        String token = line.buyer().token();
        String base = "/api/v1/outlets/" + line.buyer().outletId() + "/wallet/transactions";

        var history = s.api.get(token, base + "?kinds=CREDIT_REPAYMENT").at("/data/items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("key").asText()).isEqualTo("L" + entry);
        assertThat(history.get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(history.get(0).get("bill").isNull()).isTrue();
        assertThat(history.get(0).get("reason").asText()).isEqualTo("Credit repayment to ABC store");

        var detail = s.api.get(token, base + "/" + entry).at("/data");
        assertThat(detail.get("kind").asText()).isEqualTo("CREDIT_REPAYMENT");
        assertThat(detail.get("counterpartyName").asText()).isEqualTo("ABC store");
        String numbers = jdbc.queryForList("select invoice_number from credit_invoice where credit_agreement_id = ? "
                + "order by id", String.class, line.agreementId()).stream().reduce((a, b) -> a + ", " + b).orElseThrow();
        assertThat(detail.get("counterpartyDetail").asText()).isEqualTo(numbers);
        assertThat(detail.get("amount").decimalValue()).isEqualByComparingTo("10500.00");
        assertThat(detail.get("balanceAfter").decimalValue()).isEqualByComparingTo("9500.00");
    }

    // ── the supplier-recorded path still works, and now locks ────────────

    @Test
    @DisplayName("a payment the supplier records still reduces the debt and the exposure, as SUPPLIER_RECORDED")
    void supplierRecordedPaymentStillWorks() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();

        var reply = s.recordPayment(line.seller(), fx.first(), "2500.00");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(invoiceStatus(fx.first())).isEqualTo("PARTIALLY_PAID");
        assertThat(paid(fx.first())).isEqualByComparingTo("2500.00");
        assertThat(utilized(line)).isEqualByComparingTo("8000.00");
        var row = jdbc.queryForMap("select source, method, credit_repayment_id from credit_payment "
                + "where credit_invoice_id = ?", fx.first());
        assertThat(row.get("source")).isEqualTo("SUPPLIER_RECORDED");
        assertThat(row.get("method")).isEqualTo("BANK_TRANSFER");
        assertThat(row.get("credit_repayment_id")).isNull();
        assertThat(events("CreditRepaymentRecorded", line.agreementId())).isEqualTo(1);
        assertThat(events("CreditRepaymentReceived", line.agreementId())).isZero();
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("20000.00");
        // Paying the rest settles it, and the overpayment guard still holds.
        assertThat(s.recordPayment(line.seller(), fx.first(), "4000.01").status()).isEqualTo(400);
        assertThat(s.recordPayment(line.seller(), fx.first(), "4000.00").status()).isEqualTo(200);
        assertThat(invoiceStatus(fx.first())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("the supplier's recording locks the invoice row: it allocates against what was committed while it waited")
    void supplierRecordedPaymentLocksTheInvoice() throws Exception {
        var fx = fx("20000.00");
        var line = fx.line();
        var holder = new Holder(
                "select id from credit_invoice where id = " + fx.first() + " for update",
                "update credit_invoice set paid_amount = 3000, status = 'PARTIALLY_PAID', version = version + 1 "
                        + "where id = " + fx.first(),
                "update credit_agreement set utilized_amount = utilized_amount - 3000 where id = " + line.agreementId());

        var call = pool.submit(() -> s.recordPayment(line.seller(), fx.first(), "3500.00"));
        Thread.sleep(1500);
        assertThat(call.isDone()).describedAs("the recording must wait for the invoice").isFalse();
        holder.release();

        var reply = call.get(30, TimeUnit.SECONDS);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(invoiceStatus(fx.first())).isEqualTo("PAID");
        assertThat(paid(fx.first())).isEqualByComparingTo("6500.00");
        assertThat(utilized(line)).isEqualByComparingTo("4000.00");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void relayEvents(String type, long aggregateId) {
        for (var event : jdbc.queryForList("select event_id, event_type, aggregate_type, aggregate_id, "
                + "payload_version, payload, actor_id, correlation_id, occurred_at from outbox_event "
                + "where event_type = ? and aggregate_id = ? order by id", type, aggregateId)) {
            relay.publish(new OutboxPublisher.DomainEventEnvelope(
                    (String) event.get("event_id"), (String) event.get("event_type"),
                    (String) event.get("aggregate_type"), ((Number) event.get("aggregate_id")).longValue(),
                    ((Number) event.get("payload_version")).intValue(), String.valueOf(event.get("payload")),
                    null, (String) event.get("correlation_id"), Instant.now()));
        }
    }
}
