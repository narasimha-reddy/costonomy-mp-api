package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditInvoiceService;
import com.costonomy.mp.settlement.service.SettlementService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.TestOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Money-safety edge cases on the credit feature: what a failure or a refusal must leave untouched, and what a
 * cancel-after-the-draw credit note (B7, D-152). Every test asserts the rows, not only the status.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditMoneySafetyIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementService settlementService;
    @Autowired private com.costonomy.mp.payment.service.PaymentJobs paymentJobs;

    /** Spied only to make the second invoice of a repayment fail (F12). */
    @SpyBean private CreditInvoiceService invoiceSpy;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(invoiceSpy);
    }

    private Reply pay(Line line, String amount, Long... invoiceIds) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("amount", amount);
        if (invoiceIds.length > 0) {
            body.put("invoiceIds", List.of(invoiceIds));
        }
        return s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(), body);
    }

    private long payouts(Line line) {
        return e.count("select count(*) from credit_repayment_payout p join credit_repayment r "
                + "on r.id = p.credit_repayment_id where r.outlet_id = ?", line.buyer().outletId());
    }

    // ── F12 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("F12: a failure on the second invoice, after the wallet debit and the payout, rolls the whole repayment back")
    void failureAfterTheDebitRollsEverythingBack() throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "65", 100);   // 6500
        long second = s.invoice(line, "40", 100);  // 4000
        s.topUp(line.buyer(), "20000.00");
        var before = s.snapshot(line);
        long payoutsBefore = payouts(line);

        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("boom: the second invoice failed after the debit");
            }
            return call.callRealMethod();
        }).when(invoiceSpy).applyPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        String key = UUID.randomUUID().toString();

        // 7000: all of invoice 1 (6500) and 500 of invoice 2, so two applications, the second of which throws.
        var reply = s.repay(line.buyer().token(), line.agreementId(), key,
                Map.of("amount", "7000.00", "invoiceIds", List.of(first, second)));

        assertThat(reply.status()).describedAs(reply.body().toString()).isGreaterThanOrEqualTo(500);
        assertThat(calls.get()).describedAs("both applications were attempted: the debit really happened first").isEqualTo(2);
        var after = s.snapshot(line);
        assertThat(after.wallet()).describedAs("the wallet is untouched").isEqualByComparingTo(before.wallet());
        assertThat(after.walletRows()).describedAs("no wallet row").isEqualTo(before.walletRows());
        assertThat(after.repayments()).describedAs("no credit_repayment").isEqualTo(before.repayments());
        assertThat(after.creditPayments()).describedAs("no credit_payment, not even the first invoice's").isEqualTo(before.creditPayments());
        assertThat(payouts(line)).describedAs("no payout").isEqualTo(payoutsBefore);
        assertThat(after.utilized()).isEqualByComparingTo(before.utilized());
        assertThat(after.invoices()).describedAs("neither invoice moved").isEqualTo(before.invoices());
        assertThat(after.ledgerRows()).isEqualTo(before.ledgerRows());
        assertThat(after.outbox()).describedAs("no event for a repayment that did not happen").isEqualTo(before.outbox());
        assertThat(jdbc.queryForObject("select state from idempotency_record where operation = 'credit.wallet-repay' "
                + "and idempotency_key = ?", String.class, key)).isEqualTo("FAILED");

        // The system is not wedged: the same amount with a fresh key goes through once, and only once.
        calls.set(-100);
        var again = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "7000.00", "invoiceIds", List.of(first, second)));
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(201);
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("13000.00");
        assertThat(s.snapshot(line).repayments()).isEqualTo(before.repayments() + 1);
        assertThat(payouts(line)).isEqualTo(payoutsBefore + 1);
    }

    // ── F15, M08, M10 ────────────────────────────────────────────────────

    @Test
    @DisplayName("F15: the exposure has drifted below what the invoices say is owed; the repayment is refused whole and nothing moves")
    void exposureDriftRefusesTheRepaymentWhole() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        jdbc.update("update credit_agreement set utilized_amount = 100 where id = ?", line.agreementId());
        var before = s.snapshot(line);

        var reply = pay(line, "1000.00", invoice);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(s.snapshot(line)).describedAs("the debit, the repayment and the payment were all undone").isEqualTo(before);
        assertThat(payouts(line)).isZero();
    }

    @Test
    @DisplayName("M08: a 16-digit amount is a validation error; the largest 15-digit amount is an overpayment; nothing moves")
    void hugeAmountsAreRefused() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        var before = s.snapshot(line);

        var sixteen = pay(line, "1234567890123456", invoice);
        assertThat(sixteen.status()).describedAs(sixteen.body().toString()).isEqualTo(400);
        assertThat(sixteen.code()).isEqualTo("VALIDATION_ERROR");

        var fifteen = pay(line, "999999999999999.99", invoice);
        assertThat(fifteen.status()).describedAs(fifteen.body().toString()).isEqualTo(422);
        assertThat(fifteen.code()).isEqualTo("CREDIT_OVERPAYMENT");

        assertThat(s.snapshot(line)).isEqualTo(before);
        assertThat(e.count("select count(*) from idempotency_record where operation = 'credit.wallet-repay' "
                + "and state = 'COMPLETED' and actor_id = ?", e.userId(line.buyer().token()))).isZero();
    }

    @Test
    @DisplayName("M10: two invoices with the same due date; a payment smaller than the first takes it all from the lower id")
    void sameDueDateIsPaidInIdOrder() throws Exception {
        var line = s.creditLine("200000");
        long lower = s.invoice(line, "65", 100);
        long higher = s.invoice(line, "40", 100);
        s.topUp(line.buyer(), "20000.00");
        jdbc.update("update credit_invoice set due_date = (select due_date from (select due_date from credit_invoice "
                + "where id = ?) d) where id = ?", lower, higher);
        assertThat(jdbc.queryForObject("select due_date from credit_invoice where id = ?", java.sql.Date.class, higher))
                .isEqualTo(jdbc.queryForObject("select due_date from credit_invoice where id = ?", java.sql.Date.class, lower));

        var reply = pay(line, "1000.00");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(reply.data().get("allocations")).hasSize(1);
        assertThat(reply.data().at("/allocations/0/invoiceId").asLong()).isEqualTo(lower);
        assertThat(jdbc.queryForObject("select paid_amount from credit_invoice where id = ?", BigDecimal.class, lower))
                .isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select paid_amount from credit_invoice where id = ?", BigDecimal.class, higher))
                .isEqualByComparingTo("0");
    }

    // ── L07 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L07: a restaurant with an ACTIVE line to supplier A and a SUSPENDED line to B; the order on B is refused as suspended and leaves nothing reserved on either")
    void suspendedLineLeavesNoReservationOnTheActiveOne() throws Exception {
        // Since D-088 an order is one supplier's: arrangeFunding is only ever given one order, so a literal
        // multi-supplier cart cannot be submitted. What is left to protect is this: the refusal of B's order
        // must not reserve, or leave a reservation row, on A, and B itself must hold nothing.
        var buyer = s.newBuyer();
        var a = s.creditLine(buyer, "100000", Map.of());
        var b = s.creditLine(buyer, "100000", Map.of());
        assertThat(e.suspend(b, "Awaiting payment").status()).isEqualTo(200);
        long intentA = e.answeredRequest(a, "100", 10);
        long intentB = e.answeredRequest(b, "100", 10);

        var refused = e.orderOnCredit(buyer, intentB);

        assertThat(refused.status()).describedAs(refused.body().toString()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("CREDIT_SUSPENDED");
        for (Line line : List.of(a, b)) {
            var row = e.agreementRow(line.agreementId());
            assertThat(e.dec(row, "reserved_amount")).isEqualByComparingTo("0");
            assertThat(e.dec(row, "utilized_amount")).isEqualByComparingTo("0");
            assertThat(e.count("select count(*) from credit_reservation where credit_agreement_id = ?",
                    line.agreementId())).describedAs("no reservation row, not even a failed one").isZero();
            assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ?",
                    line.agreementId())).isZero();
        }
        // And A still works, drawing only its own order.
        var ok = e.orderOnCredit(buyer, intentA);
        assertThat(ok.status()).describedAs(ok.body().toString()).isIn(200, 201);
        assertThat(e.dec(e.agreementRow(a.agreementId()), "utilized_amount")).isEqualByComparingTo("1000.00");
        assertThat(e.dec(e.agreementRow(b.agreementId()), "utilized_amount")).isEqualByComparingTo("0");
    }

    // ── X06 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("X06: wallet 300 + supplier-recorded 300 + a confirmed claim of 400 settle a 1000 invoice once; exposure is restored, the statement chains, and a further claim of 1 is refused")
    void threePaymentSourcesOnOneInvoice() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10); // 1000
        s.topUp(line.buyer(), "5000.00");
        var claim = e.claim(line.buyer().token(), invoice, "400.00");
        assertThat(claim.status()).describedAs(claim.body().toString()).isEqualTo(201);
        long claimId = claim.data().get("id").asLong();

        var wallet = pay(line, "300.00", invoice);
        assertThat(wallet.status()).describedAs(wallet.body().toString()).isEqualTo(201);
        var supplier = s.recordPayment(line.seller(), invoice, "300.00");
        assertThat(supplier.status()).describedAs(supplier.body().toString()).isEqualTo(200);
        var confirmed = e.confirm(line.seller().token(), claimId, null);
        assertThat(confirmed.status()).describedAs(confirmed.body().toString()).isEqualTo(200);
        assertThat(confirmed.data().get("confirmedAmount").decimalValue()).isEqualByComparingTo("400.00");

        var row = jdbc.queryForMap("select status, paid_amount, amount, settled_at from credit_invoice where id = ?", invoice);
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat((BigDecimal) row.get("paid_amount")).isEqualByComparingTo("1000.00");
        assertThat(row.get("settled_at")).isNotNull();
        var payments = jdbc.queryForList("select source, amount from credit_payment where credit_invoice_id = ? order by id",
                invoice);
        assertThat(payments).extracting(p -> p.get("source").toString())
                .containsExactly("WALLET", "SUPPLIER_RECORDED", "CLAIM_CONFIRMED");
        assertThat(payments.stream().map(p -> (BigDecimal) p.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000.00");

        var agreement = e.agreementRow(line.agreementId());
        assertThat(e.dec(agreement, "utilized_amount")).describedAs("exposure restored exactly").isEqualByComparingTo("0");
        assertThat(e.dec(agreement, "reserved_amount")).isEqualByComparingTo("0");
        var read = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId()).at("/data");
        assertThat(read.get("available").decimalValue()).isEqualByComparingTo("200000");

        // The statement: opening + sum of lines = closing, and each line's owedAfter is the running total.
        var statement = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/statement")
                .at("/data");
        BigDecimal running = statement.get("openingOwed").decimalValue();
        var lines = new ArrayList<JsonNode>();
        statement.get("lines").forEach(lines::add);
        java.util.Collections.reverse(lines); // newest first on the wire
        assertThat(lines).extracting(l -> l.get("type").asText()).contains("UTILIZE", "REPAYMENT");
        for (JsonNode l : lines) {
            running = running.add(l.get("amount").decimalValue());
            assertThat(l.get("owedAfter").decimalValue()).describedAs(l.toString()).isEqualByComparingTo(running);
        }
        assertThat(running).isEqualByComparingTo(statement.get("closingOwed").decimalValue());
        assertThat(statement.get("closingOwed").decimalValue()).isEqualByComparingTo("0");
        assertThat(lines.stream().filter(l -> "REPAYMENT".equals(l.get("type").asText())).count()).isEqualTo(3);

        // A further claim, even of ₹1, has nothing left to claim against.
        var further = e.claim(line.buyer().token(), invoice, "1.00");
        assertThat(further.status()).describedAs(further.body().toString()).isEqualTo(422);
        assertThat(further.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(e.count("select count(*) from credit_payment_claim where credit_invoice_id = ?", invoice)).isEqualTo(1);
    }

    // ── S39 and S40 ──────────────────────────────────────────────────────

    private record Placed(Line line, long orderId, long invoiceId) {
    }

    private Placed confirmedCreditOrder() throws Exception {
        var line = s.creditLine("200000");
        long intent = e.answeredRequest(line, "400", 100);
        var order = e.orderOnCredit(line.buyer(), intent);
        assertThat(order.status()).describedAs(order.body().toString()).isIn(200, 201);
        long orderId = order.data().get("supplierOrderId").asLong();
        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, orderId))
                .isEqualTo("CONFIRMED");
        long invoice = jdbc.queryForObject("select id from credit_invoice where supplier_order_id = ?", Long.class, orderId);
        return new Placed(line, orderId, invoice);
    }

    /**
     * The correct behaviour (B7, D-152): an order cancelled after the draw owes nothing. The debt is cleared by a SYSTEM
     * credit note in the cancel transaction, the credit is free again, the ledger and the statement show the note,
     * the restaurant is told, and no payout, commission or refund is involved.
     */
    private void assertDebtClearedByACreditNote(Placed placed, long payoutsBefore) throws Exception {
        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, placed.orderId()))
                .isEqualTo("CANCELLED");
        var agreement = e.agreementRow(placed.line().agreementId());
        assertThat(e.dec(agreement, "utilized_amount")).describedAs("the debt for goods that will not arrive is gone")
                .isEqualByComparingTo("0");
        assertThat(e.dec(agreement, "reserved_amount")).isEqualByComparingTo("0");
        var read = s.api.get(placed.line().buyer().token(), "/api/v1/credit/agreements/" + placed.line().agreementId()).at("/data");
        assertThat(read.get("available").decimalValue()).describedAs("the credit is available again").isEqualByComparingTo("200000");
        assertThat(read.get("due").decimalValue()).isEqualByComparingTo("0");

        var invoice = jdbc.queryForMap("select status, paid_amount, credited_amount, amount from credit_invoice where id = ?",
                placed.invoiceId());
        assertThat(invoice.get("status")).describedAs("nothing is owed, so it is settled").isEqualTo("PAID");
        assertThat((BigDecimal) invoice.get("credited_amount")).isEqualByComparingTo("40000.00");
        assertThat((BigDecimal) invoice.get("paid_amount")).describedAs("credited, not paid").isEqualByComparingTo("0");

        var note = jdbc.queryForMap("select credit_note_number, kind, reason_code, amount, created_by, note from credit_note "
                + "where credit_invoice_id = ?", placed.invoiceId());
        assertThat(note.get("kind")).isEqualTo("SYSTEM_CANCEL");
        assertThat(note.get("reason_code")).isEqualTo("CANCELLED");
        assertThat((BigDecimal) note.get("amount")).isEqualByComparingTo("40000.00");
        assertThat(note.get("created_by")).describedAs("issued by the system").isNull();
        assertThat(e.count("select count(*) from credit_transaction where credit_agreement_id = ? "
                + "and transaction_type = 'CREDIT_NOTE' and credit_invoice_id = ?", placed.line().agreementId(), placed.invoiceId()))
                .describedAs("the ledger shows the credit note").isEqualTo(1);
        assertThat(e.count("select count(*) from outbox_event where event_type = 'CreditNoteIssued' and aggregate_id = ? "
                + "and json_extract(payload, '$.outletId') = ?", placed.invoiceId(), placed.line().buyer().outletId()))
                .describedAs("the restaurant is told").isEqualTo(1);

        var statement = s.api.get(placed.line().buyer().token(), "/api/v1/credit/agreements/" + placed.line().agreementId()
                + "/statement").at("/data");
        assertThat(statement.get("closingOwed").decimalValue()).isEqualByComparingTo("0");
        assertThat(statement.get("lines").toString()).contains("Credit note").contains((String) note.get("credit_note_number"));

        assertThat(payouts(placed.line())).describedAs("no payout").isEqualTo(payoutsBefore);
        assertThat(e.count("select count(*) from credit_repayment where outlet_id = ?", placed.line().buyer().outletId())).isZero();
        assertThat(e.count("select count(*) from credit_payment where credit_agreement_id = ?", placed.line().agreementId())).isZero();
        assertThat(e.count("select count(*) from credit_refund_due where credit_invoice_id = ?", placed.invoiceId()))
                .describedAs("nothing was paid, so nothing is owed back").isZero();
    }

    @Test
    @DisplayName("S39: the supplier cancels a CONFIRMED credit order: a system credit note clears the debt (B7, D-152)")
    void supplierCancelsConfirmedCreditOrder() throws Exception {
        var placed = confirmedCreditOrder();
        long payoutsBefore = payouts(placed.line());

        var cancel = e.call("POST", placed.line().seller().token(),
                "/api/v1/supplier-orders/" + placed.orderId() + "/supplier-cancel", UUID.randomUUID().toString(),
                Map.of("reason", "OUT_OF_STOCK"));

        assertThat(cancel.status()).describedAs(cancel.body().toString()).isEqualTo(200);
        assertDebtClearedByACreditNote(placed, payoutsBefore);
    }

    @Test
    @DisplayName("S40: the restaurant cancels a CONFIRMED credit order: a system credit note clears the debt (B7, D-152)")
    void restaurantCancelsConfirmedCreditOrder() throws Exception {
        var placed = confirmedCreditOrder();
        long payoutsBefore = payouts(placed.line());

        var cancel = e.call("POST", placed.line().buyer().token(),
                "/api/v1/supplier-orders/" + placed.orderId() + "/cancel", UUID.randomUUID().toString(),
                Map.of("reason", "Changed our plans"));

        assertThat(cancel.status()).describedAs(cancel.body().toString()).isEqualTo(200);
        assertDebtClearedByACreditNote(placed, payoutsBefore);
    }

    // ── T15 ──────────────────────────────────────────────────────────────

    private static Instant utcMidnight(String day) {
        return ZonedDateTime.of(java.time.LocalDate.parse(day).atStartOfDay(), ZoneId.of("UTC")).toInstant();
    }

    /** The settlement queries bind java.sql.Timestamp, which renders in the JVM's zone while MySQL runs in UTC. */
    private static Instant dbWallClock(Instant instant) {
        return instant.minusSeconds(ZoneId.systemDefault().getRules().getOffset(instant).getTotalSeconds());
    }

    private int generateForUtcDay(String day) {
        Instant start = utcMidnight(day);
        return settlementService.generate(dbWallClock(start), dbWallClock(start.plusSeconds(86_400)));
    }

    @Test
    @DisplayName("T15: payouts made at 00:10 IST on 1 March (18:40 UTC on the 28th) and at exactly 00:00 UTC on the 28th are applied once each, in the settlement of UTC day 28 February")
    void payoutNearTheDayBoundaryIsAppliedExactlyOnce() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        long late = pay(line, "100.00", invoice).data().get("repaymentId").asLong();
        long boundary = pay(line, "200.00", invoice).data().get("repaymentId").asLong();
        // 2026-03-01T00:10+05:30 is 2026-02-28T18:40Z. The other lands exactly on a UTC midnight.
        jdbc.update("update credit_repayment_payout set created_at = '2026-02-28 18:40:00.000000' "
                + "where credit_repayment_id = ?", late);
        jdbc.update("update credit_repayment_payout set created_at = '2026-02-28 00:00:00.000000' "
                + "where credit_repayment_id = ?", boundary);
        long store = line.seller().storeId();

        // The day before: both payouts are made on or after its end, so neither belongs to it.
        generateForUtcDay("2026-02-27");
        assertThat(e.count("select count(*) from credit_repayment_payout where supplier_store_id = ? and status = 'PENDING'",
                store)).describedAs("27 Feb must not take a payout made on the 28th").isEqualTo(2);
        assertThat(e.count("select count(*) from settlement where supplier_store_id = ?", store)).isZero();

        // The day it was made, in UTC: both applied, in this one settlement.
        generateForUtcDay("2026-02-28");
        assertThat(e.count("select count(*) from settlement where supplier_store_id = ?", store)).isEqualTo(1);
        long settlement = jdbc.queryForObject("select id from settlement where supplier_store_id = ?", Long.class, store);
        assertThat(e.count("select count(*) from credit_repayment_payout where supplier_store_id = ? and status = 'APPLIED' "
                + "and settlement_id = ?", store, settlement)).isEqualTo(2);

        // The following days, and the same day again: nothing is applied a second time, nothing new appears.
        generateForUtcDay("2026-03-01");
        generateForUtcDay("2026-02-28");
        generateForUtcDay("2026-03-02");
        assertThat(e.count("select count(*) from settlement where supplier_store_id = ?", store)).isEqualTo(1);
        assertThat(e.count("select count(*) from settlement_adjustment a join settlement st on st.id = a.settlement_id "
                + "where st.supplier_store_id = ? and a.reason_code = 'CREDIT_REPAYMENT'", store))
                .describedAs("one credit adjustment per payout, never more").isEqualTo(2);
        assertThat(jdbc.queryForObject("select coalesce(sum(a.amount), 0) from settlement_adjustment a "
                + "join settlement st on st.id = a.settlement_id where st.supplier_store_id = ? "
                + "and a.reason_code = 'CREDIT_REPAYMENT' and a.direction = 'CREDIT'", BigDecimal.class, store))
                .isEqualByComparingTo("300.00");
        assertThat(e.count("select count(*) from credit_repayment_payout where supplier_store_id = ? and settlement_id = ?",
                store, settlement)).isEqualTo(2);
    }

    // ── F06 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("F06: a dispute on a credit order cannot be refunded to the wallet: refused as settled with the supplier, and nothing is credited or charged")
    void disputeRefundOnACreditOrderIsRefused() throws Exception {
        var line = s.creditLine("200000");
        var buyer = line.buyer();
        var seller = line.seller();
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, seller.storeId());
        long productId = com.costonomy.mp.support.TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = s.api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId, "name", "Paneer",
                        "packSize", 1, "packUnit", "KG", "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
        var placed = new TestOrder(mvc, json, s.api).place(buyer.token(), buyer.outletId(), seller.token(), skuId,
                10, 10, "SUPPLIER_DELIVERY", "CREDIT", null);
        long orderId = placed.orderId();
        for (String step : List.of("preparing", "ready")) {
            assertThat(e.call("POST", seller.token(), "/api/v1/supplier-orders/" + orderId + "/" + step,
                    UUID.randomUUID().toString(), null).status()).isEqualTo(200);
        }
        paymentJobs.capturePending();
        long deliveryId = e.call("POST", seller.token(), "/api/v1/supplier-orders/" + orderId + "/delivery",
                UUID.randomUUID().toString(), null).data().get("id").asLong();
        s.api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/dispatched", Map.of());
        s.api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/delivered", Map.of());
        long itemId = jdbc.queryForObject("select id from supplier_order_item where supplier_order_id = ?", Long.class,
                orderId);
        var received = e.call("POST", buyer.token(), "/api/v1/supplier-orders/" + orderId + "/receive",
                UUID.randomUUID().toString(), Map.of("items", List.of(Map.of("supplierOrderItemId", itemId,
                        "receivedQuantity", "10", "damagedQuantity", "0", "missingQuantity", "0"))));
        assertThat(received.status()).describedAs(received.body().toString()).isEqualTo(200);
        var dispute = e.call("POST", buyer.token(), "/api/v1/supplier-orders/" + orderId + "/disputes",
                UUID.randomUUID().toString(), Map.of("category", "QUALITY", "description", "Two packs were sour."));
        assertThat(dispute.status()).describedAs(dispute.body().toString()).isEqualTo(200);
        long disputeId = dispute.data().get("id").asLong();
        var utilizedBefore = e.dec(e.agreementRow(line.agreementId()), "utilized_amount");
        assertThat(utilizedBefore).isEqualByComparingTo("4000.00");

        // The restaurant asks: refused, and the reason says why.
        var ask = e.call("POST", buyer.token(), "/api/v1/disputes/" + disputeId + "/refund-request",
                UUID.randomUUID().toString(), Map.of("amount", "500.00", "reason", "Sour paneer"));
        assertThat(ask.status()).describedAs(ask.body().toString()).isEqualTo(409);
        assertThat(ask.code()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(ask.body().at("/error/message").asText()).contains("settled with your supplier directly");
        var limit = s.api.get(buyer.token(), "/api/v1/disputes/" + disputeId + "/refund-limit").at("/data");
        assertThat(limit.get("maxAmount").decimalValue()).isEqualByComparingTo("0");

        // Even a request that somehow exists (seeded here) cannot be paid out by the supplier.
        jdbc.update("insert into dispute_refund (dispute_id, supplier_order_id, outlet_id, supplier_store_id, amount, "
                + "reason, status, requested_by, created_at, updated_at, version) select ?, ?, ?, ?, 500, 'seeded', "
                + "'REQUESTED', u.id, now(6), now(6), 0 from users u order by u.id limit 1",
                disputeId, orderId, buyer.outletId(), seller.storeId());
        long requestId = jdbc.queryForObject("select id from dispute_refund where dispute_id = ?", Long.class, disputeId);
        var approve = e.call("POST", seller.token(), "/api/v1/dispute-refunds/" + requestId + "/approve",
                UUID.randomUUID().toString(), Map.of());
        assertThat(approve.status()).describedAs(approve.body().toString()).isBetween(400, 499);

        assertThat(jdbc.queryForObject("select status from dispute_refund where id = ?", String.class, requestId))
                .isEqualTo("REQUESTED");
        assertThat(s.balance(buyer)).describedAs("no wallet credit").isEqualByComparingTo("0");
        assertThat(e.count("select count(*) from wallet_transaction t join wallet w on w.id = t.wallet_id "
                + "where w.outlet_id = ?", buyer.outletId())).isZero();
        assertThat(e.count("select count(*) from refund where supplier_order_id = ?", orderId)).isZero();
        assertThat(e.count("select count(*) from supplier_deduction where supplier_order_id = ?", orderId))
                .describedAs("no charge to the supplier's payout").isZero();
        assertThat(e.dec(e.agreementRow(line.agreementId()), "utilized_amount")).isEqualByComparingTo(utilizedBefore);
    }
}
