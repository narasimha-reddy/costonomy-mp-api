package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditPaySupport.Inv;
import com.costonomy.mp.credit.CreditPaySupport.Setup;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

/**
 * What happens when an invoice changes between the restaurant tapping Pay and the capture being applied (D-196,
 * D-180 extended): a changed invoice is skipped and its share goes back to the card or UPI; the others are paid; and a
 * supplier's receipt, a wallet repayment, a credit note or a cancellation racing the capture always ends consistent.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayRaceIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @Autowired private CreditJobs creditJobs;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;

    @BeforeEach
    void setUp() {
        reset(provider);
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
    }

    /**
     * Pays the standard setup's three invoices up to the capture, lets {@code change} act on the invoices, then
     * applies the capture. Returns the attempt.
     */
    private CreditPaySupport.Paid captureAfter(Setup setup, Change change) throws Exception {
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        change.run();
        var reply = p.verify(setup.buyer(), paid.attemptId(), paid.paymentId());
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return paid;
    }

    @FunctionalInterface
    private interface Change {
        void run() throws Exception;
    }

    /** The invoice at index {@code skipped} was skipped for {@code reason}; the other two were paid. */
    private void assertOneSkipped(Setup setup, CreditPaySupport.Paid paid, int skipped, String reason,
                                  String refundAmount) {
        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("PAID");
        for (int i = 0; i < 3; i++) {
            var row = p.attemptInvoice(paid.attemptId(), setup.inv(i));
            if (i == skipped) {
                assertThat(row.get("outcome")).isEqualTo("SKIPPED");
                assertThat(row.get("skip_reason")).isEqualTo(reason);
                assertThat(row.get("credit_payment_refund_id")).isNotNull();
                assertThat(row.get("credit_repayment_id")).isNull();
                assertThat(row.get("credit_payment_id")).isNull();
            } else {
                assertThat(row.get("outcome")).isEqualTo("PAID");
                assertThat(row.get("skip_reason")).isNull();
                assertThat(p.invoiceStatus(setup.inv(i))).isEqualTo("PAID");
            }
        }
        var refunds = p.refundRows(paid.attemptId());
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).get("reason")).isEqualTo("SKIPPED_INVOICES");
        assertThat(refunds.get(0).get("destination")).isEqualTo("ORIGINAL");
        assertThat(refunds.get(0).get("razorpay_payment_id")).isEqualTo(paid.paymentId());
        assertThat(refunds.get(0).get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) refunds.get(0).get("amount")).isEqualByComparingTo(refundAmount);
        // The restaurant is told about the refund.
        assertThat(p.events("CreditPaymentRefund", paid.attemptId())).isEqualTo(1);
        for (var line : setup.lines()) {
            p.n.assertConsistent(line);
        }
        // Nothing has gone back yet; the job sends it.
        assertThat(provider.refundedOf(paid.paymentId())).isEqualByComparingTo("0");
        p.refunds();
        assertThat(provider.refundedOf(paid.paymentId())).isEqualByComparingTo(refundAmount);
        assertThat(p.refundRows(paid.attemptId()).get(0).get("status")).isEqualTo("COMPLETED");
    }

    // ── an invoice that changed before the capture ───────────────────────

    @Test
    @DisplayName("a credit note on one invoice: that one is SKIPPED AMOUNT_CHANGED, the others are paid, its share is refunded")
    void creditNote() throws Exception {
        var setup = p.standard();

        var paid = captureAfter(setup, () -> assertThat(p.creditNote(setup.inv(1), "500.00").status()).isEqualTo(201));

        assertOneSkipped(setup, paid, 1, "AMOUNT_CHANGED", "4000.00");
        // The skipped invoice is as the supplier left it, not touched by the capture.
        assertThat((BigDecimal) p.n.inv(setup.inv(1).id()).get("paid_amount")).isEqualByComparingTo("0");
        assertThat(p.n.outstanding(setup.inv(1).id())).isEqualByComparingTo("3500.00");
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 2);
        // One repayment per supplier line that was paid, each for what its paid invoices took.
        assertThat(p.jdbc.queryForObject("select amount from credit_repayment where credit_payment_attempt_id = ? "
                + "and credit_agreement_id = ?", BigDecimal.class, paid.attemptId(), setup.line(0).agreementId()))
                .isEqualByComparingTo("6500.00");
        assertThat(p.jdbc.queryForObject("select o.amount from credit_repayment_payout o join credit_repayment r "
                + "on r.id = o.credit_repayment_id where r.credit_payment_attempt_id = ? and r.credit_agreement_id = ?",
                BigDecimal.class, paid.attemptId(), setup.line(0).agreementId())).isEqualByComparingTo("6500.00");
        // The restaurant's note names the amount and how many invoices changed.
        var payload = json.readTree(p.jdbc.queryForObject("select payload from outbox_event "
                + "where event_type = 'CreditPaymentRefund' and json_extract(payload, '$.attemptId') = ?",
                String.class, paid.attemptId()));
        assertThat(payload.get("skippedText").asText()).isEqualTo("1 invoice");
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("4000.00");
        p.relayEvents("CreditPaymentRefund", paid.attemptId());
        var told = p.jdbc.queryForList("select title, body, audience from notification "
                + "where event_type = 'CreditPaymentRefund'");
        assertThat(told).isNotEmpty();
    }

    @Test
    @DisplayName("a write-off of one invoice: SKIPPED WRITTEN_OFF")
    void writeOff() throws Exception {
        var setup = p.standard();

        var paid = captureAfter(setup, () -> assertThat(p.writeOff(setup.inv(2)).status()).isIn(200, 201));

        assertOneSkipped(setup, paid, 2, "WRITTEN_OFF", "1980.00");
        assertThat(p.invoiceStatus(setup.inv(2))).isEqualTo("WRITTEN_OFF");
    }

    @Test
    @DisplayName("the supplier recorded the full amount in cash meanwhile: SKIPPED SETTLED")
    void supplierReceiptInFull() throws Exception {
        var setup = p.standard();

        var paid = captureAfter(setup, () -> assertThat(p.supplierReceipt(setup.inv(0), "6500.00").status()).isEqualTo(200));

        assertOneSkipped(setup, paid, 0, "SETTLED", "6500.00");
        assertThat((BigDecimal) p.n.inv(setup.inv(0).id()).get("paid_amount")).isEqualByComparingTo("6500.00");
        assertThat(p.jdbc.queryForObject("select count(*) from credit_payment where credit_invoice_id = ? "
                + "and source = 'ONLINE'", Long.class, setup.inv(0).id())).isZero();
    }

    @Test
    @DisplayName("a part receipt by the supplier meanwhile: SKIPPED AMOUNT_CHANGED, the whole share is refunded")
    void supplierPartReceipt() throws Exception {
        var setup = p.standard();

        var paid = captureAfter(setup, () -> assertThat(p.supplierReceipt(setup.inv(1), "1000.00").status()).isEqualTo(200));

        assertOneSkipped(setup, paid, 1, "AMOUNT_CHANGED", "4000.00");
        assertThat(p.n.outstanding(setup.inv(1).id())).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("the supplier reversed a receipt meanwhile, so more is owed than was charged: SKIPPED AMOUNT_CHANGED")
    void supplierReversalRaisesOutstanding() throws Exception {
        var setup = p.standard();
        assertThat(p.supplierReceipt(setup.inv(1), "1000.00").status()).isEqualTo(200);

        var paid = captureAfter(setup, () -> assertThat(p.reverseLatestReceipt(setup.inv(1)).status()).isEqualTo(200));

        assertOneSkipped(setup, paid, 1, "AMOUNT_CHANGED", "3000.00");
        assertThat(p.n.outstanding(setup.inv(1).id())).isEqualByComparingTo("4000.00");
    }

    @Test
    @DisplayName("a status move to OVERDUE and a due-date extension in between are not changes: everything is paid")
    void overdueSweepAndExtensionInBetween() throws Exception {
        var setup = p.standard();

        var paid = captureAfter(setup, () -> {
            p.s.age(setup.inv(0).id(), 3);
            creditJobs.sweepOverdue();
            assertThat(p.invoiceStatus(setup.inv(0))).isEqualTo("OVERDUE");
            var extend = p.e.call("POST", setup.line(1).seller().token(),
                    "/api/v1/credit/invoices/" + setup.inv(2).id() + "/extend-due", UUID.randomUUID().toString(),
                    Map.of("newDueDate", CreditEdgeSupport.today().plusDays(20).toString(), "reason", "More time"));
            assertThat(extend.status()).describedAs(extend.body().toString()).isEqualTo(200);
        });

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("PAID");
        p.assertAppliedOnce(setup, paid.attemptId(), 2, 3);
        assertThat(p.refundRows(paid.attemptId())).isEmpty();
    }

    @Test
    @DisplayName("a supplier store deactivated after create: its invoices are SKIPPED SUPPLIER_UNAVAILABLE and refunded; the other supplier is paid")
    void storeDeactivatedAfterCreate() throws Exception {
        var setup = p.standard();

        var paid = captureAfter(setup, () -> p.deactivateStore(setup.line(1)));

        assertOneSkipped(setup, paid, 2, "SUPPLIER_UNAVAILABLE", "1980.00");
        assertThat(p.repayments(paid.attemptId())).isEqualTo(1);
        assertThat(p.payouts(paid.attemptId())).isEqualTo(1);
        assertThat(p.invoiceStatus(setup.inv(2))).isNotEqualTo("PAID");
    }

    @Test
    @DisplayName("every invoice changed: the attempt is REFUNDED, no repayment or payout, and the whole total_charged goes back")
    void everythingChanged() throws Exception {
        var setup = p.standard();
        var before = p.snapshot(setup);

        var paid = captureAfter(setup, () -> {
            assertThat(p.supplierReceipt(setup.inv(0), "6500.00").status()).isEqualTo(200);
            assertThat(p.supplierReceipt(setup.inv(1), "100.00").status()).isEqualTo(200);
            assertThat(p.creditNote(setup.inv(2), "80.00").status()).isEqualTo(201);
        });

        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("REFUNDED");
        assertThat(p.attempt(paid.attemptId()).get("paid_at")).isNotNull();
        assertThat(p.attempt(paid.attemptId()).get("open_selection_key")).isNull();
        assertThat(p.attempt(paid.attemptId()).get("razorpay_payment_id")).isEqualTo(paid.paymentId());
        assertThat(p.repayments(paid.attemptId())).isZero();
        assertThat(p.payouts(paid.attemptId())).isZero();
        assertThat(p.creditPayments(paid.attemptId())).isZero();
        assertThat(p.events("CreditRepaymentReceived", paid.attemptId())).isZero();
        assertThat(p.events("CreditPaymentCompleted", paid.attemptId())).isZero();
        assertThat(p.events("CreditPaymentRefund", paid.attemptId())).isEqualTo(1);
        assertThat(p.auditsFor("CREDIT_PAY_ATTEMPT_REFUNDED", paid.attemptId())).isEqualTo(1);
        for (var row : p.attemptInvoices(paid.attemptId())) {
            assertThat(row.get("outcome")).isEqualTo("SKIPPED");
        }
        var refunds = p.refundRows(paid.attemptId());
        assertThat(refunds).hasSize(1);
        assertThat((BigDecimal) refunds.get(0).get("amount")).isEqualByComparingTo("12480.00");
        // The supplier's own changes stand and nothing else moved on the invoices.
        assertThat(p.s.snapshot(setup.line(0)).repayments()).isZero();
        for (var line : setup.lines()) {
            p.n.assertConsistent(line);
        }
        p.refunds();
        assertThat(provider.refundedOf(paid.paymentId())).isEqualByComparingTo("12480.00");
        assertThat(before.attempts()).isZero();
        var read = p.status(setup.buyer(), paid.attemptId()).data();
        assertThat(read.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(read.get("refundAmount").decimalValue()).isEqualByComparingTo("12480.00");
        assertThat(read.get("refundState").asText()).isEqualTo("COMPLETED");
    }

    // ── true concurrency ─────────────────────────────────────────────────

    @Test
    @DisplayName("a supplier's receipt racing the capture, ten times: paid once by us and the supplier refused, or the supplier first and our share refunded")
    void supplierReceiptRacesCapture() throws Exception {
        int weWon = 0;
        int supplierWon = 0;
        for (int run = 1; run <= 10; run++) {
            var setup = p.single("4000.00");
            var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
            List<Callable<Reply>> calls = List.of(
                    () -> p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()),
                    () -> p.supplierReceipt(setup.inv(0), "4000.00"));

            var results = p.concurrently(calls);

            String describe = "run " + run + " " + results.get(0).body() + " " + results.get(1).body();
            assertThat(results.get(0).status()).describedAs(describe).isEqualTo(200);
            assertThat(p.invoiceStatus(setup.inv(0))).describedAs(describe).isEqualTo("PAID");
            assertThat((BigDecimal) p.n.inv(setup.inv(0).id()).get("paid_amount")).describedAs(describe)
                    .isEqualByComparingTo("4000.00");
            assertThat(p.utilized(setup.line(0))).describedAs(describe).isEqualByComparingTo("0");
            p.n.assertConsistent(setup.line(0));
            if ("PAID".equals(p.attemptStatus(paid.attemptId()))) {
                weWon++;
                assertThat(results.get(1).status()).describedAs(describe).isGreaterThanOrEqualTo(400);
                assertThat(p.repayments(paid.attemptId())).describedAs(describe).isEqualTo(1);
                assertThat(p.refundRows(paid.attemptId())).describedAs(describe).isEmpty();
                assertThat(p.count("select count(*) from credit_payment where credit_invoice_id = ?",
                        setup.inv(0).id())).describedAs(describe).isEqualTo(1);
            } else {
                supplierWon++;
                assertThat(p.attemptStatus(paid.attemptId())).describedAs(describe).isEqualTo("REFUNDED");
                assertThat(results.get(1).status()).describedAs(describe).isEqualTo(200);
                assertThat(p.repayments(paid.attemptId())).describedAs(describe).isZero();
                var refunds = p.refundRows(paid.attemptId());
                assertThat(refunds).describedAs(describe).hasSize(1);
                assertThat((BigDecimal) refunds.get(0).get("amount")).describedAs(describe)
                        .isEqualByComparingTo("4000.00");
            }
        }
        assertThat(weWon + supplierWon).isEqualTo(10);
    }

    @Test
    @DisplayName("a wallet repayment of the same invoice racing the capture, ten times: one pays, the other is refused or refunded; money is never lost or doubled")
    void walletRepaymentRacesCapture() throws Exception {
        for (int run = 1; run <= 10; run++) {
            var setup = p.single("4000.00");
            p.s.topUp(setup.buyer(), "10000.00");
            var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
            List<Callable<Reply>> calls = List.of(
                    () -> p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()),
                    () -> p.s.repay(setup.buyer().token(), setup.line(0).agreementId(), UUID.randomUUID().toString(),
                            Map.of("amount", "4000.00", "invoiceIds", List.of(setup.inv(0).id()))));

            var results = p.concurrently(calls);

            String describe = "run " + run + " " + results.get(0).body() + " " + results.get(1).body();
            assertThat(results.get(0).status()).describedAs(describe).isEqualTo(200);
            assertThat(p.invoiceStatus(setup.inv(0))).describedAs(describe).isEqualTo("PAID");
            assertThat((BigDecimal) p.n.inv(setup.inv(0).id()).get("paid_amount")).describedAs(describe)
                    .isEqualByComparingTo("4000.00");
            assertThat(p.utilized(setup.line(0))).describedAs(describe).isEqualByComparingTo("0");
            p.n.assertConsistent(setup.line(0));
            if ("PAID".equals(p.attemptStatus(paid.attemptId()))) {
                assertThat(results.get(1).status()).describedAs(describe).isGreaterThanOrEqualTo(400);
                assertThat(p.s.balance(setup.buyer())).describedAs(describe + ": wallet untouched")
                        .isEqualByComparingTo("10000.00");
                assertThat(p.refundRows(paid.attemptId())).describedAs(describe).isEmpty();
            } else {
                assertThat(p.attemptStatus(paid.attemptId())).describedAs(describe).isEqualTo("REFUNDED");
                assertThat(results.get(1).status()).describedAs(describe).isEqualTo(201);
                assertThat(p.s.balance(setup.buyer())).describedAs(describe + ": wallet paid once")
                        .isEqualByComparingTo("6000.00");
                assertThat(p.refundRows(paid.attemptId())).describedAs(describe).hasSize(1);
            }
        }
    }

    @Test
    @DisplayName("two attempts that both include one invoice, both captured: the first pays it, the second skips it and refunds that share")
    void overlappingAttempts() throws Exception {
        var setup = p.standard();
        var first = p.createAndPay(setup.buyer(), p.items(setup.inv(0), setup.inv(1)));
        var second = p.createAndPay(setup.buyer(), p.items(setup.inv(1), setup.inv(2)));

        assertThat(p.verify(setup.buyer(), first.attemptId(), first.paymentId()).status()).isEqualTo(200);
        assertThat(p.verify(setup.buyer(), second.attemptId(), second.paymentId()).status()).isEqualTo(200);

        assertThat(p.attemptStatus(first.attemptId())).isEqualTo("PAID");
        assertThat(p.attemptStatus(second.attemptId())).isEqualTo("PAID");
        assertThat(p.attemptInvoice(second.attemptId(), setup.inv(1)).get("outcome")).isEqualTo("SKIPPED");
        assertThat(p.attemptInvoice(second.attemptId(), setup.inv(1)).get("skip_reason")).isEqualTo("SETTLED");
        assertThat(p.attemptInvoice(second.attemptId(), setup.inv(2)).get("outcome")).isEqualTo("PAID");
        var refunds = p.refundRows(second.attemptId());
        assertThat(refunds).hasSize(1);
        assertThat((BigDecimal) refunds.get(0).get("amount")).isEqualByComparingTo("4000.00");
        assertThat((BigDecimal) p.n.inv(setup.inv(1).id()).get("paid_amount")).isEqualByComparingTo("4000.00");
        for (var line : setup.lines()) {
            p.n.assertConsistent(line);
        }
    }

    // ── cancelling an order that was paid online ─────────────────────────

    private record Placed(Setup setup, long orderId) {
    }

    /** A credit order of ₹40,000 with its invoice, as {@code CreditCancelCreditNoteIT} places one. */
    private Placed placedOrder() throws Exception {
        var line = p.s.creditLine("200000", Map.of());
        long intent = p.e.answeredRequest(line, "400", 100);
        var order = p.e.orderOnCredit(line.buyer(), intent);
        assertThat(order.status()).describedAs(order.body().toString()).isIn(200, 201);
        long orderId = order.data().get("supplierOrderId").asLong();
        long invoice = jdbc.queryForObject("select id from credit_invoice where supplier_order_id = ?", Long.class, orderId);
        return new Placed(new Setup(line.buyer(), List.of(line), List.of(new Inv(line, invoice))), orderId);
    }

    private Reply supplierCancels(Placed placed) throws Exception {
        return p.e.call("POST", placed.setup().line(0).seller().token(),
                "/api/v1/supplier-orders/" + placed.orderId() + "/supplier-cancel", UUID.randomUUID().toString(),
                Map.of("reason", "OUT_OF_STOCK"));
    }

    private List<Map<String, Object>> refundsDue(long invoice) {
        return jdbc.queryForList("select id, credit_note_id, amount, channel, status, note from credit_refund_due "
                + "where credit_invoice_id = ? order by id", invoice);
    }

    @Test
    @DisplayName("an order cancelled after it was paid online: an ONLINE refund-due for ops, not OFF_PLATFORM; no money moves and the payout stays")
    void cancellationAfterOnlinePayment() throws Exception {
        var placed = placedOrder();
        var setup = placed.setup();
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);
        String payoutStatus = jdbc.queryForObject("select o.status from credit_repayment_payout o join credit_repayment r "
                + "on r.id = o.credit_repayment_id where r.credit_payment_attempt_id = ?", String.class, paid.attemptId());

        var cancel = supplierCancels(placed);

        assertThat(cancel.status()).describedAs(cancel.body().toString()).isEqualTo(200);
        var due = refundsDue(setup.inv(0).id());
        assertThat(due).hasSize(1);
        assertThat(due.get(0).get("channel")).describedAs("Mandi holds this money, the supplier cannot return it")
                .isEqualTo("ONLINE");
        assertThat((BigDecimal) due.get(0).get("amount")).isEqualByComparingTo("40000.00");
        assertThat(due.get(0).get("note")).isEqualTo("paid online through Mandi: ops refund");
        assertThat(due.get(0).get("credit_note_id")).isNull();
        assertThat(p.n.audits("CREDIT_REFUND_DUE_OPS", ((Number) due.get(0).get("id")).longValue())).isEqualTo(1);
        // Nothing moves yet: no Razorpay refund, the payout is as it was.
        assertThat(provider.refundedOf(paid.paymentId())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select o.status from credit_repayment_payout o join credit_repayment r "
                + "on r.id = o.credit_repayment_id where r.credit_payment_attempt_id = ?", String.class,
                paid.attemptId())).isEqualTo(payoutStatus);
        assertThat(p.attemptStatus(paid.attemptId())).isEqualTo("PAID");
        // The supplier cannot mark it refunded.
        var refused = p.e.call("POST", setup.line(0).seller().token(),
                "/api/v1/credit/refunds-due/" + due.get(0).get("id") + "/mark-refunded", null, Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("CREDIT_REFUND_OPS_ONLY");
        p.n.assertConsistent(setup.line(0));
    }

    @Test
    @DisplayName("part paid in cash by the supplier and the rest online, then cancelled: OFF_PLATFORM for the cash, ONLINE for the online part")
    void cancellationSplitsCashAndOnline() throws Exception {
        var placed = placedOrder();
        var setup = placed.setup();
        assertThat(p.supplierReceipt(setup.inv(0), "5000.00").status()).isEqualTo(200);
        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);

        assertThat(supplierCancels(placed).status()).isEqualTo(200);

        var due = refundsDue(setup.inv(0).id());
        assertThat(due).hasSize(2);
        var byChannel = new java.util.HashMap<String, BigDecimal>();
        for (var row : due) {
            byChannel.put((String) row.get("channel"), (BigDecimal) row.get("amount"));
        }
        assertThat(byChannel).containsOnlyKeys("OFF_PLATFORM", "ONLINE");
        assertThat(byChannel.get("OFF_PLATFORM")).isEqualByComparingTo("5000.00");
        assertThat(byChannel.get("ONLINE")).isEqualByComparingTo("35000.00");
    }
}
