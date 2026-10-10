package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditCancellationService;
import com.costonomy.mp.credit.service.CreditFundingAdapter;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * An order cancelled after the draw (B7, D-176, D-177): the debt for goods that will not arrive is cleared by a system
 * credit note in the cancel transaction; money already paid on the invoice cannot be cancelled by a note and becomes a
 * refund due, which the supplier settles off-platform (a wallet-funded part is flagged for ops and moves nothing here).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditCancelCreditNoteIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private CreditFundingAdapter funding;
    @Autowired private NotificationRelayAccess relay;
    @SpyBean private CreditCancellationService cancellationSpy;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private CreditNoteSupport n;
    private CreditLifecycleSupport l;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        n = new CreditNoteSupport(e, jdbc);
        l = new CreditLifecycleSupport(e, jdbc, relay);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        org.mockito.Mockito.reset(cancellationSpy);
    }

    private record Placed(Line line, long orderId, long invoiceId) {
    }

    /** A line of ₹2,00,000 and a CONFIRMED credit order of ₹40,000 with its invoice. */
    private Placed placed() throws Exception {
        return placedOn(s.creditLine("200000", Map.of()), "400", 100);
    }

    private Placed placedOn(Line line, String unitPrice, int quantity) throws Exception {
        long intent = e.answeredRequest(line, unitPrice, quantity);
        var order = e.orderOnCredit(line.buyer(), intent);
        assertThat(order.status()).describedAs(order.body().toString()).isIn(200, 201);
        long orderId = order.data().get("supplierOrderId").asLong();
        long invoice = jdbc.queryForObject("select id from credit_invoice where supplier_order_id = ?", Long.class, orderId);
        return new Placed(line, orderId, invoice);
    }

    private Reply supplierCancels(Placed p) throws Exception {
        return e.call("POST", p.line().seller().token(), "/api/v1/supplier-orders/" + p.orderId() + "/supplier-cancel",
                UUID.randomUUID().toString(), Map.of("reason", "OUT_OF_STOCK"));
    }

    private Reply restaurantCancels(Placed p) throws Exception {
        return e.call("POST", p.line().buyer().token(), "/api/v1/supplier-orders/" + p.orderId() + "/cancel",
                UUID.randomUUID().toString(), Map.of("reason", "Changed our plans"));
    }

    private List<Map<String, Object>> refunds(long invoice) {
        return jdbc.queryForList("select id, credit_note_id, amount, channel, status, note from credit_refund_due "
                + "where credit_invoice_id = ? order by id", invoice);
    }

    private Map<String, Object> refund(long invoice, String channel) {
        return refunds(invoice).stream().filter(r -> channel.equals(r.get("channel"))).findFirst().orElseThrow();
    }

    // ── OR08: part paid ──────────────────────────────────────────────────

    @Test
    @DisplayName("OR08: cancel after a part payment of ₹15,000 on ₹40,000: a note for the ₹25,000 owed and a refund due of ₹15,000 the supplier settles")
    void partPaidIsCreditedAndTheRestIsARefundDue() throws Exception {
        var p = placed();
        assertThat(s.recordPayment(p.line().seller(), p.invoiceId(), "15000.00").status()).isEqualTo(200);

        var cancel = supplierCancels(p);

        assertThat(cancel.status()).describedAs(cancel.body().toString()).isEqualTo(200);
        var note = jdbc.queryForMap("select id, kind, amount, created_by from credit_invoice_note where credit_invoice_id = ?", p.invoiceId());
        assertThat(note.get("kind")).isEqualTo("SYSTEM_CANCEL");
        assertThat((BigDecimal) note.get("amount")).describedAs("only what was still owed").isEqualByComparingTo("25000");
        assertThat(note.get("created_by")).isNull();
        var invoice = n.inv(p.invoiceId());
        assertThat((BigDecimal) invoice.get("credited_amount")).isEqualByComparingTo("25000");
        assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("15000");
        assertThat(invoice.get("status")).isEqualTo("PAID");
        assertThat(n.utilized(p.line())).isEqualByComparingTo("0");

        var due = refunds(p.invoiceId());
        assertThat(due).hasSize(1);
        assertThat((BigDecimal) due.get(0).get("amount")).isEqualByComparingTo("15000");
        assertThat(due.get(0).get("channel")).isEqualTo("OFF_PLATFORM");
        assertThat(due.get(0).get("status")).isEqualTo("OPEN");
        assertThat(due.get(0).get("credit_note_id")).isEqualTo(note.get("id"));
        assertThat(n.events("CreditRefundDue", p.invoiceId())).isEqualTo(1);
        n.assertConsistent(p.line());

        // The supplier sees it.
        var list = e.call("GET", p.line().seller().token(), "/api/v1/supplier-stores/" + p.line().seller().storeId()
                + "/credit/refunds-due?status=OPEN", null, null);
        assertThat(list.status()).describedAs(list.body().toString()).isEqualTo(200);
        assertThat(list.data()).hasSize(1);
        assertThat(list.data().get(0).get("amount").decimalValue()).isEqualByComparingTo("15000.00");
        assertThat(list.data().get(0).get("status").asText()).isEqualTo("OPEN");
        assertThat(list.data().get(0).get("channel").asText()).isEqualTo("OFF_PLATFORM");
        assertThat(list.data().get(0).get("invoiceNumber").asText()).startsWith("INV-");
        assertThat(list.data().get(0).get("creditNoteNumber").asText()).startsWith("CLN-");
        assertThat(list.data().get(0).get("restaurantName").asText()).isEqualTo("Paradise");
        assertThat(e.call("GET", p.line().seller().token(), "/api/v1/supplier-stores/" + p.line().seller().storeId()
                + "/credit/refunds-due?status=REFUNDED", null, null).data()).isEmpty();
    }

    @Test
    @DisplayName("OR08: an earlier manual note is not double counted: the cancel note is the outstanding at that moment")
    void cancelNoteIsOnlyWhatIsStillOwed() throws Exception {
        var p = placed();
        n.noteOk(p.line(), p.invoiceId(), "5000.00");

        assertThat(restaurantCancels(p).status()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select amount from credit_invoice_note where credit_invoice_id = ? and kind = 'SYSTEM_CANCEL'",
                BigDecimal.class, p.invoiceId())).isEqualByComparingTo("35000");
        assertThat(n.credited(p.invoiceId())).isEqualByComparingTo("40000");
        assertThat(n.status(p.invoiceId())).isEqualTo("PAID");
        assertThat(refunds(p.invoiceId())).isEmpty();
        n.assertConsistent(p.line());
    }

    @Test
    @DisplayName("cancel of an already PAID invoice: no note (nothing is owed), a refund due for what was paid")
    void paidInvoiceGetsARefundDueOnly() throws Exception {
        var p = placed();
        assertThat(s.recordPayment(p.line().seller(), p.invoiceId(), "40000.00").status()).isEqualTo(200);
        assertThat(n.status(p.invoiceId())).isEqualTo("PAID");

        assertThat(supplierCancels(p).status()).isEqualTo(200);

        assertThat(n.notesOf(p.invoiceId())).describedAs("a note cannot reach money already paid").isZero();
        var due = refunds(p.invoiceId());
        assertThat(due).hasSize(1);
        assertThat((BigDecimal) due.get(0).get("amount")).isEqualByComparingTo("40000");
        assertThat(due.get(0).get("credit_note_id")).isNull();
        assertThat(due.get(0).get("channel")).isEqualTo("OFF_PLATFORM");
        assertThat(n.status(p.invoiceId())).isEqualTo("PAID");
        assertThat((BigDecimal) n.inv(p.invoiceId()).get("paid_amount")).isEqualByComparingTo("40000");
        assertThat(n.utilized(p.line())).isEqualByComparingTo("0");
        n.assertConsistent(p.line());
    }

    // ── OR09: wallet ─────────────────────────────────────────────────────

    @Test
    @DisplayName("OR09: the paid part came from the wallet: a WALLET refund due for ops, flagged and audited; no wallet or payout money moves")
    void walletFundedPartIsFlaggedForOps() throws Exception {
        var p = placed();
        s.topUp(p.line().buyer(), "20000.00");
        var repaid = s.repay(p.line().buyer().token(), p.line().agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "10000.00"));
        assertThat(repaid.status()).describedAs(repaid.body().toString()).isEqualTo(201);
        assertThat(s.recordPayment(p.line().seller(), p.invoiceId(), "5000.00").status()).isEqualTo(200);
        var before = s.snapshot(p.line());
        long payoutsBefore = n.payouts(p.line());
        String payoutStatus = jdbc.queryForObject("select p.status from credit_repayment_payout p join credit_repayment r "
                + "on r.id = p.credit_repayment_id where r.outlet_id = ?", String.class, p.line().buyer().outletId());

        assertThat(supplierCancels(p).status()).isEqualTo(200);

        assertThat((BigDecimal) jdbc.queryForObject("select amount from credit_invoice_note where credit_invoice_id = ?",
                BigDecimal.class, p.invoiceId())).isEqualByComparingTo("25000");
        var wallet = refund(p.invoiceId(), "WALLET");
        assertThat((BigDecimal) wallet.get("amount")).isEqualByComparingTo("10000");
        assertThat(wallet.get("status")).isEqualTo("OPEN");
        assertThat((String) wallet.get("note")).isEqualTo("wallet-funded: ops refund");
        var cash = refund(p.invoiceId(), "OFF_PLATFORM");
        assertThat((BigDecimal) cash.get("amount")).isEqualByComparingTo("5000");
        assertThat(n.audits("CREDIT_REFUND_DUE_OPS", ((Number) wallet.get("id")).longValue())).describedAs("the alert row for ops").isEqualTo(1);

        var after = s.snapshot(p.line());
        assertThat(after.wallet()).describedAs("no wallet money moved").isEqualByComparingTo(before.wallet());
        assertThat(after.walletRows()).isEqualTo(before.walletRows());
        assertThat(after.repayments()).isEqualTo(before.repayments());
        assertThat(n.payouts(p.line())).describedAs("the payout is left exactly as it was").isEqualTo(payoutsBefore);
        assertThat(jdbc.queryForObject("select p.status from credit_repayment_payout p join credit_repayment r "
                + "on r.id = p.credit_repayment_id where r.outlet_id = ?", String.class, p.line().buyer().outletId()))
                .isEqualTo(payoutStatus);

        // The supplier cannot clear the wallet part: only ops can.
        var token = p.line().seller().token();
        var refused = e.call("POST", token, "/api/v1/credit/refunds-due/" + wallet.get("id") + "/mark-refunded", null, Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("CREDIT_REFUND_OPS_ONLY");
        assertThat(refund(p.invoiceId(), "WALLET").get("status")).isEqualTo("OPEN");
        assertThat(e.call("POST", token, "/api/v1/credit/refunds-due/" + cash.get("id") + "/mark-refunded", null, Map.of()).status())
                .isEqualTo(200);
        n.assertConsistent(p.line());
    }

    // ── mark refunded ────────────────────────────────────────────────────

    @Test
    @DisplayName("mark-refunded: CREDIT_COLLECT, idempotent, audited; a restaurant, a salesperson and another supplier get 404")
    void markRefunded() throws Exception {
        var p = placed();
        assertThat(s.recordPayment(p.line().seller(), p.invoiceId(), "15000.00").status()).isEqualTo(200);
        assertThat(supplierCancels(p).status()).isEqualTo(200);
        long id = ((Number) refund(p.invoiceId(), "OFF_PLATFORM").get("id")).longValue();
        String path = "/api/v1/credit/refunds-due/" + id + "/mark-refunded";

        for (String token : List.of(p.line().buyer().token(), l.staff(p.line(), "SUP_SALESPERSON"), s.newSeller().token())) {
            assertThat(e.call("POST", token, path, null, Map.of()).status()).isEqualTo(404);
        }
        assertThat(e.call("POST", p.line().seller().token(), "/api/v1/credit/refunds-due/987654321/mark-refunded", null, Map.of()).status())
                .isEqualTo(404);
        assertThat(refund(p.invoiceId(), "OFF_PLATFORM").get("status")).isEqualTo("OPEN");

        var manager = l.staff(p.line(), "SUP_STORE_MANAGER");
        var done = e.call("POST", manager, path, null, Map.of("note", "Sent by bank transfer, ref 123"));
        assertThat(done.status()).describedAs(done.body().toString()).isEqualTo(200);
        assertThat(done.data().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(done.data().get("refundedAt").isNull()).isFalse();
        var row = jdbc.queryForMap("select status, refunded_by, refunded_at, note from credit_refund_due where id = ?", id);
        assertThat(row.get("status")).isEqualTo("REFUNDED");
        assertThat(row.get("refunded_by")).isNotNull();
        assertThat(n.audits("CREDIT_REFUND_MARKED", id)).isEqualTo(1);

        var again = e.call("POST", p.line().seller().token(), path, null, Map.of());
        assertThat(again.status()).describedAs("already in the state asked for").isEqualTo(200);
        assertThat(again.data().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(n.audits("CREDIT_REFUND_MARKED", id)).isEqualTo(1);
        assertThat(e.call("GET", p.line().seller().token(), "/api/v1/supplier-stores/" + p.line().seller().storeId()
                + "/credit/refunds-due", null, null).data()).hasSize(1);
        assertThat(e.call("GET", p.line().seller().token(), "/api/v1/supplier-stores/" + p.line().seller().storeId()
                + "/credit/refunds-due?status=OPEN", null, null).data()).isEmpty();
        // Another store's user cannot list this one's.
        assertThat(e.call("GET", s.newSeller().token(), "/api/v1/supplier-stores/" + p.line().seller().storeId()
                + "/credit/refunds-due", null, null).status()).isEqualTo(404);
        assertThat(e.call("GET", p.line().buyer().token(), "/api/v1/supplier-stores/" + p.line().seller().storeId()
                + "/credit/refunds-due", null, null).status()).isEqualTo(404);
    }

    // ── idempotency, atomicity ───────────────────────────────────────────

    @Test
    @DisplayName("the same cancellation delivered twice makes one note and one refund due (cancel again, and the hook called directly)")
    void doubleDeliveryIsOneNote() throws Exception {
        var p = placed();
        assertThat(s.recordPayment(p.line().seller(), p.invoiceId(), "15000.00").status()).isEqualTo(200);
        assertThat(supplierCancels(p).status()).isEqualTo(200);
        long notes = n.notesOf(p.invoiceId());
        long ledger = n.ledgerRows(p.line(), "CREDIT_NOTE");
        long events = n.events("CreditNoteIssued", p.invoiceId());
        long refundRows = n.refundsOf(p.invoiceId());
        var invoice = n.inv(p.invoiceId());

        assertThat(supplierCancels(p).status()).describedAs("cancelling a cancelled order is a no-op").isEqualTo(200);
        funding.onOrderUnfulfilled(p.orderId(), "Cancelled: again");
        funding.onOrderUnfulfilled(p.orderId(), "Cancelled: again");

        assertThat(notes).isEqualTo(1);
        assertThat(n.notesOf(p.invoiceId())).isEqualTo(notes);
        assertThat(n.ledgerRows(p.line(), "CREDIT_NOTE")).isEqualTo(ledger);
        assertThat(n.events("CreditNoteIssued", p.invoiceId())).isEqualTo(events);
        assertThat(n.refundsOf(p.invoiceId())).isEqualTo(refundRows);
        assertThat(n.inv(p.invoiceId())).isEqualTo(invoice);
        assertThat(n.utilized(p.line())).isEqualByComparingTo("0");
        n.assertConsistent(p.line());
    }

    @Test
    @DisplayName("CC07: if the credit note fails inside the cancel, the whole cancel rolls back: order still CONFIRMED, debt and invoice untouched")
    void failureRollsTheCancelBack() throws Exception {
        var p = placed();
        var before = s.snapshot(p.line());
        doThrow(new IllegalStateException("boom")).when(cancellationSpy).onCancelledAfterDraw(any(), any());

        var cancel = supplierCancels(p);

        assertThat(cancel.status()).describedAs(cancel.body().toString()).isGreaterThanOrEqualTo(500);
        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, p.orderId()))
                .describedAs("no half state").isEqualTo("CONFIRMED");
        assertThat(s.snapshot(p.line())).isEqualTo(before);
        assertThat(n.notesOf(p.invoiceId())).isZero();
        assertThat(n.utilized(p.line())).isEqualByComparingTo("40000");

        org.mockito.Mockito.reset(cancellationSpy);
        assertThat(restaurantCancels(p).status()).describedAs("not wedged: a retry goes through").isEqualTo(200);
        assertThat(n.utilized(p.line())).isEqualByComparingTo("0");
    }

    // ── races ────────────────────────────────────────────────────────────

    private List<Reply> concurrently(List<Callable<Reply>> calls) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Reply>>();
        for (var call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        var out = new ArrayList<Reply>();
        for (var f : futures) {
            out.add(f.get(90, TimeUnit.SECONDS));
        }
        return out;
    }

    @Test
    @DisplayName("CC: a cancel racing a recorded payment: the lock order holds, nothing goes negative, and what was paid is owed back")
    void cancelRacingAPayment() throws Exception {
        for (int round = 0; round < 3; round++) {
            var p = placedOn(s.creditLine("200000", Map.of()), "10", 100);   // 1,000
            var replies = concurrently(List.of(() -> supplierCancels(p),
                    () -> s.recordPayment(p.line().seller(), p.invoiceId(), "400.00")));

            assertThat(replies.get(0).status()).describedAs(replies.toString()).isEqualTo(200);
            int payment = replies.get(1).status();
            assertThat(payment).describedAs(replies.toString()).isIn(200, 400);
            assertThat(n.outstanding(p.invoiceId())).isEqualByComparingTo("0");
            assertThat(n.status(p.invoiceId())).isEqualTo("PAID");
            assertThat(n.utilized(p.line())).isEqualByComparingTo("0");
            if (payment == 200) {
                assertThat((BigDecimal) n.inv(p.invoiceId()).get("paid_amount")).isEqualByComparingTo("400");
                assertThat(n.credited(p.invoiceId())).isEqualByComparingTo("600");
                assertThat(refunds(p.invoiceId())).describedAs("what was paid is owed back").hasSize(1);
                assertThat((BigDecimal) refunds(p.invoiceId()).get(0).get("amount")).isEqualByComparingTo("400");
            } else {
                assertThat(n.credited(p.invoiceId())).isEqualByComparingTo("1000");
                assertThat(refunds(p.invoiceId())).isEmpty();
            }
            n.assertConsistent(p.line());
        }
    }

    @Test
    @DisplayName("CC: a cancel racing a wallet repayment of the same invoice ends consistent, wallet money accounted for")
    void cancelRacingAWalletRepayment() throws Exception {
        for (int round = 0; round < 2; round++) {
            var p = placedOn(s.creditLine("200000", Map.of()), "10", 100);
            s.topUp(p.line().buyer(), "5000.00");
            var replies = concurrently(List.of(() -> supplierCancels(p),
                    () -> s.repay(p.line().buyer().token(), p.line().agreementId(), UUID.randomUUID().toString(),
                            Map.of("amount", "400.00", "invoiceIds", List.of(p.invoiceId())))));

            assertThat(replies.get(0).status()).describedAs(replies.toString()).isEqualTo(200);
            assertThat(replies.get(1).status()).describedAs(replies.toString()).isIn(201, 404, 422);
            assertThat(n.outstanding(p.invoiceId())).isEqualByComparingTo("0");
            BigDecimal walletPaid = jdbc.queryForObject("select coalesce(sum(amount), 0) from credit_payment "
                    + "where credit_invoice_id = ? and source = 'WALLET'", BigDecimal.class, p.invoiceId());
            BigDecimal owedBack = jdbc.queryForObject("select coalesce(sum(amount), 0) from credit_refund_due "
                    + "where credit_invoice_id = ? and channel = 'WALLET'", BigDecimal.class, p.invoiceId());
            assertThat(owedBack).describedAs("every rupee paid from the wallet is flagged").isEqualByComparingTo(walletPaid);
            n.assertConsistent(p.line());
        }
    }

    // ── what else a cancel does ──────────────────────────────────────────

    @Test
    @DisplayName("a cancel that clears the overdue lifts the sweep's suspension in the same transaction")
    void cancelReinstatesALineTheSweepSuspended() throws Exception {
        var line = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
        var p = placedOn(line, "400", 100);
        s.age(p.invoiceId(), 10);
        creditJobs.sweepOverdue();
        assertThat(l.status(line.agreementId())).isEqualTo("SUSPENDED");

        assertThat(supplierCancels(p).status()).isEqualTo(200);

        assertThat(l.status(line.agreementId())).isEqualTo("ACTIVE");
        assertThat(n.status(p.invoiceId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a claim still waiting on the cancelled invoice is closed as superseded; confirming it later is refused")
    void waitingClaimIsSuperseded() throws Exception {
        var p = placed();
        long claimId = e.claim(p.line().buyer().token(), p.invoiceId(), "5000.00").data().get("id").asLong();

        assertThat(supplierCancels(p).status()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select status from credit_payment_claim where id = ?", String.class, claimId))
                .isEqualTo("SUPERSEDED");
        assertThat(e.confirm(p.line().seller().token(), claimId, null).code()).isEqualTo("CREDIT_CLAIM_STATE");
    }

    @Test
    @DisplayName("the restaurant is told about the note once, in-app and push, never SMS; the refund due tells the supplier in-app")
    void notifications() throws Exception {
        var p = placed();
        l.registerDevice(p.line().buyer().token());
        assertThat(s.recordPayment(p.line().seller(), p.invoiceId(), "15000.00").status()).isEqualTo(200);
        assertThat(restaurantCancels(p).status()).isEqualTo(200);

        l.relayEvents("CreditNoteIssued", p.invoiceId());
        var sent = l.notificationsFor("CreditNoteIssued", p.invoiceId());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("audience")).isEqualTo("OUTLET");
        assertThat(l.deliveries("CreditNoteIssued", p.invoiceId(), "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditNoteIssued", p.invoiceId(), "SMS")).isZero();
        l.relayEvents("CreditRefundDue", p.invoiceId());
        var refundNotice = l.notificationsFor("CreditRefundDue", p.invoiceId());
        assertThat(refundNotice).hasSize(1);
        assertThat(refundNotice.get(0).get("audience")).isEqualTo("SUPPLIER_STORE");
    }

    @Test
    @DisplayName("a cancelled order's note shows on the invoice and in the list of both sides, with the system as its author")
    void notesAreVisible() throws Exception {
        var p = placed();
        assertThat(supplierCancels(p).status()).isEqualTo(200);
        for (String token : List.of(p.line().buyer().token(), p.line().seller().token())) {
            var page = n.notes(token, p.line().agreementId(), "");
            assertThat(page.status()).describedAs(page.body().toString()).isEqualTo(200);
            assertThat(page.data().at("/items/0/kind").asText()).isEqualTo("SYSTEM_CANCEL");
            assertThat(page.data().at("/items/0/reasonCode").asText()).isEqualTo("CANCELLED");
            assertThat(page.data().at("/items/0/amount").decimalValue()).isEqualByComparingTo("40000.00");
            assertThat(page.data().at("/items/0/createdBy").isNull()).isTrue();
        }
        var read = n.invoiceRead(p.line().buyer().token(), p.invoiceId()).data();
        assertThat(read.at("/creditNotes/0/kind").asText()).isEqualTo("SYSTEM_CANCEL");
    }
}
