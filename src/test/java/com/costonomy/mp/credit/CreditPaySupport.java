package com.costonomy.mp.credit;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.credit.CreditWalletSupport.Buyer;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/**
 * What the credit-pay suites share (D-196): a restaurant with lines at several suppliers and open invoices, the six
 * endpoints, the webhook, the two jobs, and readers for the rows an assertion must look at directly. Everything is
 * driven through the public endpoints; SQL is only for ageing rows and reading state back.
 *
 * <p>The jobs ({@code CreditPayJobs}) and nothing else from work package B are reached by reflection, so this file
 * compiles against the seed before they exist.
 */
final class CreditPaySupport {

    /** One invoice of a line. */
    record Inv(Line line, long id) {
    }

    /** A restaurant and what it owes: lines, in order, and their invoices, in order. */
    record Setup(Buyer buyer, List<Line> lines, List<Inv> invoices) {
        Inv inv(int index) {
            return invoices.get(index);
        }

        Line line(int index) {
            return lines.get(index);
        }
    }

    /** An attempt that was created, its checkout completed, and not yet verified. */
    record Paid(long attemptId, String orderId, String paymentId) {
    }

    static final String SIG = MockPaymentProvider.TEST_SIGNATURE;

    final CreditWalletSupport s;
    final CreditEdgeSupport e;
    final CreditNoteSupport n;
    final MockMvc mvc;
    final ObjectMapper json;
    final JdbcTemplate jdbc;
    final MockPaymentProvider provider;
    final ApplicationContext ctx;
    private final NotificationRelayAccess relay;

    CreditPaySupport(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc, MockPaymentProvider provider,
                     ApplicationContext ctx, NotificationRelayAccess relay) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.provider = provider;
        this.ctx = ctx;
        this.relay = relay;
        this.e = new CreditEdgeSupport(mvc, json, jdbc);
        this.s = e.s;
        this.n = new CreditNoteSupport(e, jdbc);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    /** An invoice of exactly {@code amount}: ten units at a tenth of it, no GST. */
    long invoiceOf(Line line, String amount) throws Exception {
        String unit = new BigDecimal(amount).divide(BigDecimal.TEN, 2, RoundingMode.UNNECESSARY).toPlainString();
        return s.invoice(line, unit, 10);
    }

    /**
     * The example of the contract: supplier one holds invoices of 6500 and 4000, supplier two one of 1980; 12480 in
     * all. Invoice indexes 0, 1 (line 0) and 2 (line 1).
     */
    Setup standard() throws Exception {
        var buyer = s.newBuyer();
        var first = s.creditLine(buyer, "200000", Map.of());
        var second = s.creditLine(buyer, "200000", Map.of());
        var invoices = new ArrayList<Inv>();
        invoices.add(new Inv(first, invoiceOf(first, "6500.00")));
        invoices.add(new Inv(first, invoiceOf(first, "4000.00")));
        invoices.add(new Inv(second, invoiceOf(second, "1980.00")));
        return new Setup(buyer, List.of(first, second), invoices);
    }

    /** One line and one invoice of {@code amount}. */
    Setup single(String amount) throws Exception {
        var buyer = s.newBuyer();
        var line = s.creditLine(buyer, "200000", Map.of());
        return new Setup(buyer, List.of(line), List.of(new Inv(line, invoiceOf(line, amount))));
    }

    /** The outstanding the app would have been shown, from the row. */
    BigDecimal outstanding(long invoiceId) {
        return jdbc.queryForObject("select amount - paid_amount - credited_amount from credit_invoice where id = ?",
                BigDecimal.class, invoiceId);
    }

    /** The items of a request: each invoice with its current outstanding, as the app sends it. */
    List<Map<String, Object>> items(Inv... invoices) {
        var out = new ArrayList<Map<String, Object>>();
        for (Inv inv : invoices) {
            out.add(item(inv.id(), outstanding(inv.id())));
        }
        return out;
    }

    static Map<String, Object> item(long invoiceId, BigDecimal outstanding) {
        var m = new LinkedHashMap<String, Object>();
        m.put("invoiceId", invoiceId);
        m.put("outstanding", outstanding.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return m;
    }

    /** All invoices of a setup. */
    List<Map<String, Object>> allItems(Setup setup) {
        return items(setup.invoices().toArray(new Inv[0]));
    }

    // ── the endpoints ────────────────────────────────────────────────────

    private String base(Buyer buyer) {
        return "/api/v1/outlets/" + buyer.outletId() + "/credit";
    }

    Reply payable(Buyer buyer) throws Exception {
        return e.call("GET", buyer.token(), base(buyer) + "/payable-invoices", null, null);
    }

    Reply quote(Buyer buyer, List<Map<String, Object>> items) throws Exception {
        return e.call("POST", buyer.token(), base(buyer) + "/payments/quote", null, Map.of("items", items));
    }

    Reply create(Buyer buyer, List<Map<String, Object>> items, String method, String key) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("method", method);
        return e.call("POST", buyer.token(), base(buyer) + "/payments", key, body);
    }

    Reply create(Buyer buyer, List<Map<String, Object>> items, String method) throws Exception {
        return create(buyer, items, method, UUID.randomUUID().toString());
    }

    /** A create that must succeed (201); returns the attempt id. */
    long createOk(Buyer buyer, List<Map<String, Object>> items, String method) throws Exception {
        var reply = create(buyer, items, method);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        return reply.data().get("attemptId").asLong();
    }

    Reply verify(Buyer buyer, long attemptId, String paymentId, String signature) throws Exception {
        return e.call("POST", buyer.token(), base(buyer) + "/payments/" + attemptId + "/verify", null,
                Map.of("razorpayPaymentId", paymentId, "razorpaySignature", signature));
    }

    Reply verify(Buyer buyer, long attemptId, String paymentId) throws Exception {
        return verify(buyer, attemptId, paymentId, SIG);
    }

    Reply status(Buyer buyer, long attemptId) throws Exception {
        return e.call("GET", buyer.token(), base(buyer) + "/payments/" + attemptId, null, null);
    }

    /** The provider's callback to /webhooks/razorpay: the flat shape the existing webhook service reads. */
    Reply webhookReply(String eventType, String orderId, String paymentId, String eventId) throws Exception {
        String body = """
                {"id":"%s","event":"%s","payment_id":"%s","order_id":"%s"}
                """.formatted(eventId, eventType, paymentId, orderId);
        var response = mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIG)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    int webhook(String eventType, String orderId, String paymentId, String eventId) throws Exception {
        return webhookReply(eventType, orderId, paymentId, eventId).status();
    }

    int capturedWebhook(String orderId, String paymentId) throws Exception {
        return webhook("payment.captured", orderId, paymentId, "evt_" + UUID.randomUUID());
    }

    // ── the customer and the clock ───────────────────────────────────────

    String orderOf(long attemptId) {
        return jdbc.queryForObject("select razorpay_order_id from credit_payment_attempt where id = ?", String.class,
                attemptId);
    }

    /** The customer completes Razorpay's checkout for the attempt's order; returns the payment id. */
    String pay(long attemptId, String method) {
        return provider.completeCheckout(orderOf(attemptId), method).providerPaymentId();
    }

    /** Create (UPI), pay with UPI, and stop before anything tells us. */
    Paid createAndPay(Buyer buyer, List<Map<String, Object>> items) throws Exception {
        long id = createOk(buyer, items, "UPI");
        return new Paid(id, orderOf(id), pay(id, "upi"));
    }

    /** Makes the attempt {@code minutes} older, in SQL on the database's own clock. */
    void ageAttempt(long attemptId, int minutes) {
        jdbc.update("update credit_payment_attempt set created_at = date_sub(created_at, interval ? minute) "
                + "where id = ?", minutes, attemptId);
    }

    /** Runs a method of the WP-B job bean without compiling against it. */
    void job(String method) {
        try {
            Class<?> type = Class.forName("com.costonomy.mp.credit.service.CreditPayJobs");
            Object bean = ctx.getBean(type);
            type.getMethod(method).invoke(bean);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new IllegalStateException("CreditPayJobs." + method + " failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("CreditPayJobs." + method + " is not there", ex);
        }
    }

    void poll() {
        job("poll");
    }

    void refunds() {
        job("refunds");
    }

    // ── the supplier's levers on an invoice ──────────────────────────────

    Reply supplierReceipt(Inv inv, String amount) throws Exception {
        return s.recordPayment(inv.line().seller(), inv.id(), amount);
    }

    Reply creditNote(Inv inv, String amount) throws Exception {
        return n.note(inv.line(), inv.id(), amount);
    }

    Reply writeOff(Inv inv) throws Exception {
        return n.writeOffInvoice(inv.line().seller().token(), inv.id(),
                CreditNoteSupport.writeOff(null, "Restaurant closed down", null), UUID.randomUUID().toString());
    }

    /** Reverses the supplier's latest recorded receipt on the invoice. */
    Reply reverseLatestReceipt(Inv inv) throws Exception {
        long payment = jdbc.queryForObject("select id from credit_payment where credit_invoice_id = ? "
                + "and source = 'SUPPLIER_RECORDED' order by id desc limit 1", Long.class, inv.id());
        return e.call("POST", inv.line().seller().token(), "/api/v1/credit/payments/" + payment + "/reverse",
                UUID.randomUUID().toString(), Map.of("reason", "Typed the wrong amount"));
    }

    void deactivateStore(Line line) {
        jdbc.update("update supplier_store set status = 'INACTIVE' where id = ?", line.seller().storeId());
    }

    void setStoreRate(Line line, String rate) {
        long org = jdbc.queryForObject("select supplier_organization_id from supplier_store where id = ?", Long.class,
                line.seller().storeId());
        jdbc.update("""
                insert into commission_configuration (scope_type, scope_id, rate_percent, config_version, description,
                    effective_from, status, created_at, updated_at, version)
                values ('SUPPLIER', ?, ?, 1, 'D-196 test', now(6) - interval 1 minute, 'ACTIVE', now(6), now(6), 0)
                """, org, new BigDecimal(rate));
    }

    // ── reading rows back ────────────────────────────────────────────────

    Map<String, Object> attempt(long attemptId) {
        return jdbc.queryForMap("select * from credit_payment_attempt where id = ?", attemptId);
    }

    String attemptStatus(long attemptId) {
        return jdbc.queryForObject("select status from credit_payment_attempt where id = ?", String.class, attemptId);
    }

    List<Map<String, Object>> attemptInvoices(long attemptId) {
        return jdbc.queryForList("select * from credit_payment_attempt_invoice where attempt_id = ? "
                + "order by credit_invoice_id", attemptId);
    }

    Map<String, Object> attemptInvoice(long attemptId, Inv inv) {
        return jdbc.queryForMap("select * from credit_payment_attempt_invoice where attempt_id = ? "
                + "and credit_invoice_id = ?", attemptId, inv.id());
    }

    List<Map<String, Object>> refundRows(long attemptId) {
        return jdbc.queryForList("select * from credit_payment_refund where attempt_id = ? order by id", attemptId);
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    long repayments(long attemptId) {
        return count("select count(*) from credit_repayment where credit_payment_attempt_id = ?", attemptId);
    }

    long creditPayments(long attemptId) {
        return count("select count(*) from credit_payment p join credit_repayment r on r.id = p.credit_repayment_id "
                + "where r.credit_payment_attempt_id = ?", attemptId);
    }

    long payouts(long attemptId) {
        return count("select count(*) from credit_repayment_payout o join credit_repayment r "
                + "on r.id = o.credit_repayment_id where r.credit_payment_attempt_id = ?", attemptId);
    }

    String invoiceStatus(Inv inv) {
        return jdbc.queryForObject("select status from credit_invoice where id = ?", String.class, inv.id());
    }

    BigDecimal utilized(Line line) {
        return n.utilized(line);
    }

    /** Outbox rows of one event type that carry this attempt id. */
    long events(String type, long attemptId) {
        return count("select count(*) from outbox_event where event_type = ? "
                + "and json_extract(payload, '$.attemptId') = ?", type, attemptId);
    }

    /** Outbox rows of one event type about an agreement. */
    long agreementEvents(String type, long agreementId) {
        return count("select count(*) from outbox_event where event_type = ? "
                + "and json_extract(payload, '$.creditAgreementId') = ?", type, agreementId);
    }

    long audits(String action) {
        return count("select count(*) from audit_log where action = ?", action);
    }

    long auditsFor(String action, long entityId) {
        return count("select count(*) from audit_log where action = ? and entity_id = ?", action, entityId);
    }

    /** Publishes the outbox rows of a type through the notification relay, so notification rows exist to read. */
    void relayEvents(String type, long attemptId) {
        for (var event : jdbc.queryForList("select event_id, event_type, aggregate_type, aggregate_id, "
                + "payload_version, payload, actor_id, correlation_id, occurred_at from outbox_event "
                + "where event_type = ? and json_extract(payload, '$.attemptId') = ? order by id", type, attemptId)) {
            relay.publish(new OutboxPublisher.DomainEventEnvelope(
                    (String) event.get("event_id"), (String) event.get("event_type"),
                    (String) event.get("aggregate_type"), ((Number) event.get("aggregate_id")).longValue(),
                    ((Number) event.get("payload_version")).intValue(), String.valueOf(event.get("payload")),
                    null, (String) event.get("correlation_id"), Instant.now()));
        }
    }

    /** One snapshot of everything a refusal must leave alone: every line of the setup plus the attempt tables. */
    record Snap(List<CreditWalletSupport.Snapshot> lines, long attempts, long attemptInvoices, long refunds,
                long idempotencyRecords, long providerRefunds) {
    }

    Snap snapshot(Setup setup) {
        var lines = new ArrayList<CreditWalletSupport.Snapshot>();
        for (Line line : setup.lines()) {
            lines.add(s.snapshot(line));
        }
        return new Snap(lines,
                count("select count(*) from credit_payment_attempt where outlet_id = ?", setup.buyer().outletId()),
                count("select count(*) from credit_payment_attempt_invoice i join credit_payment_attempt a "
                        + "on a.id = i.attempt_id where a.outlet_id = ?", setup.buyer().outletId()),
                count("select count(*) from credit_payment_refund r join credit_payment_attempt a "
                        + "on a.id = r.attempt_id where a.outlet_id = ?", setup.buyer().outletId()),
                count("select count(*) from idempotency_record where operation = 'credit.pay.create'"),
                provider.refundCount());
    }

    /**
     * What must hold once an attempt has been applied, whatever path applied it and however often it was offered:
     * one repayment and one payout per supplier line that was paid, one credit payment per paid invoice, and the
     * lines' utilised figures equal what their open invoices owe.
     */
    void assertAppliedOnce(Setup setup, long attemptId, int paidLines, int paidInvoices) {
        assertThat(repayments(attemptId)).describedAs("repayments").isEqualTo(paidLines);
        assertThat(payouts(attemptId)).describedAs("payouts").isEqualTo(paidLines);
        assertThat(creditPayments(attemptId)).describedAs("credit payments").isEqualTo(paidInvoices);
        for (Line line : setup.lines()) {
            n.assertConsistent(line);
        }
    }

    // ── concurrency ──────────────────────────────────────────────────────

    /** Runs the calls at the same moment on their own threads and returns what each gave back. */
    <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            var gate = new CountDownLatch(1);
            var ready = new CountDownLatch(calls.size());
            var futures = new ArrayList<Future<T>>();
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    gate.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            gate.countDown();
            var out = new ArrayList<T>();
            for (var f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
