package com.costonomy.mp.credit;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.credit.CreditWalletSupport.Buyer;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditClockConfig;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
 * "I paid" claims (D-125): a restaurant reports a payment made straight to the supplier, and the supplier confirms
 * or rejects it. A claim is only a statement, so every test asserts what it did NOT change as hard as what it did.
 * All tests sit in nested classes (top-level methods next to {@code @Nested} classes do not run under failsafe).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditClaimIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private NotificationRelayAccess relay;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.costonomy.mp.credit.service.CreditDirectory directorySpy;

    private CreditWalletSupport s;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static LocalDate today() {
        return LocalDate.now(CreditClockConfig.ZONE);
    }

    private Reply call(String method, String token, String path, String key, Object body) throws Exception {
        var request = "GET".equals(method)
                ? MockMvcRequestBuilders.get(path)
                : MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body == null ? Map.of() : body));
        request.header("Authorization", "Bearer " + token);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private Map<String, Object> body(String amount) {
        var b = new HashMap<String, Object>();
        b.put("amount", amount);
        b.put("method", "UPI");
        b.put("reference", "UTR" + UUID.randomUUID().toString().substring(0, 8));
        b.put("paidOn", today().toString());
        return b;
    }

    private Reply claim(Line line, long invoice, String amount) throws Exception {
        return claim(line.buyer().token(), invoice, UUID.randomUUID().toString(), body(amount));
    }

    private Reply claim(String token, long invoice, String key, Map<String, Object> body) throws Exception {
        return call("POST", token, "/api/v1/credit/invoices/" + invoice + "/claims", key, body);
    }

    private Reply confirm(Line line, long claimId, String amount) throws Exception {
        return confirm(line.seller().token(), claimId, UUID.randomUUID().toString(), amount);
    }

    private Reply confirm(String token, long claimId, String key, String amount) throws Exception {
        Map<String, Object> b = amount == null ? Map.of() : Map.of("amount", amount);
        return call("POST", token, "/api/v1/credit/claims/" + claimId + "/confirm", key, b);
    }

    private Reply reject(String token, long claimId, String reason) throws Exception {
        return call("POST", token, "/api/v1/credit/claims/" + claimId + "/reject", null, Map.of("reason", reason));
    }

    private Reply withdraw(String token, long claimId) throws Exception {
        return call("POST", token, "/api/v1/credit/claims/" + claimId + "/withdraw", null, Map.of());
    }

    private long submitted(Line line, long invoice, String amount) throws Exception {
        var reply = claim(line, invoice, amount);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        return reply.data().get("id").asLong();
    }

    private Map<String, Object> claimRow(long id) {
        return jdbc.queryForMap("select * from credit_payment_claim where id = ?", id);
    }

    private long claimCount(long invoice) {
        return jdbc.queryForObject("select count(*) from credit_payment_claim where credit_invoice_id = ?",
                Long.class, invoice);
    }

    private List<Map<String, Object>> payments(long invoice) {
        return jdbc.queryForList("select * from credit_payment where credit_invoice_id = ? order by id", invoice);
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

    private String agreementStatus(Line line) {
        return jdbc.queryForObject("select status from credit_agreement where id = ?", String.class,
                line.agreementId());
    }

    private int events(String type, long aggregateId) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = ? and aggregate_id = ?",
                Integer.class, type, aggregateId);
    }

    private void registerDevice(String token) throws Exception {
        s.api.post(token, "/api/v1/devices", Map.of("platform", "ANDROID", "pushToken", "tok-" + UUID.randomUUID(),
                "appVersion", "1.0.0", "deviceModel", "Pixel"));
    }

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

    private List<Map<String, Object>> notificationsFor(String type, long targetId) {
        return jdbc.queryForList("select id, title, body, critical, audience from notification "
                + "where event_type = ? and target_id = ?", type, targetId);
    }

    private int deliveries(String type, long targetId, String channel) {
        return jdbc.queryForObject("select count(*) from notification_delivery d "
                + "join notification n on n.id = d.notification_id "
                + "where n.event_type = ? and n.target_id = ? and d.channel = ?",
                Integer.class, type, targetId, channel);
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

    // ── a claim changes nothing ──────────────────────────────────────────

    @Nested
    @DisplayName("a claim is only a statement")
    class ClaimChangesNothing {

        @Test
        @DisplayName("claimDoesNotReduceDebt: invoice, exposure, status and the agreement's dues are unchanged")
        void claimDoesNotReduceDebt() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            var before = s.snapshot(line);
            String dueBefore = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId())
                    .at("/data/due").asText();

            var reply = claim(line, invoice, "6500.00");

            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
            assertThat(reply.data().get("status").asText()).isEqualTo("SUBMITTED");
            assertThat(claimCount(invoice)).isEqualTo(1);
            var after = s.snapshot(line);
            assertThat(after.invoices()).isEqualTo(before.invoices());
            assertThat(after.utilized()).isEqualByComparingTo(before.utilized());
            assertThat(after.creditPayments()).isEqualTo(before.creditPayments());
            assertThat(after.ledgerRows()).isEqualTo(before.ledgerRows());
            assertThat(after.wallet()).isEqualByComparingTo(before.wallet());
            assertThat(paid(invoice)).isEqualByComparingTo("0");
            assertThat(invoiceStatus(invoice)).isEqualTo("ISSUED");
            assertThat(agreementStatus(line)).isEqualTo("ACTIVE");
            var agreement = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId());
            assertThat(agreement.at("/data/due").asText()).isEqualTo(dueBefore);
            assertThat(agreement.at("/data/openClaimsAmount").decimalValue()).isEqualByComparingTo("6500.00");
        }

        @Test
        @DisplayName("an overdue invoice with a claim is still marked overdue, and the line still auto-suspends")
        void overdueWithClaimStillSweptAndSuspended() throws Exception {
            var line = s.creditLine("200000", Map.of("maxOverdueAmount", "1000"));
            long invoice = s.invoice(line, "65", 100);
            s.age(invoice, 10);
            var reply = claim(line, invoice, "6500.00");
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);

            creditJobs.sweepOverdue();

            assertThat(invoiceStatus(invoice)).isEqualTo("OVERDUE");
            assertThat(agreementStatus(line)).isEqualTo("SUSPENDED");
            assertThat(paid(invoice)).isEqualByComparingTo("0");
            assertThat(utilized(line)).isEqualByComparingTo("6500.00");
            assertThat(claimRow(reply.data().get("id").asLong()).get("status")).isEqualTo("SUBMITTED");
        }

        @Test
        @DisplayName("the supplier is notified on submit: in-app and push, never SMS, not critical")
        void supplierNotifiedOnSubmit() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            registerDevice(line.seller().token());
            String number = jdbc.queryForObject("select invoice_number from credit_invoice where id = ?",
                    String.class, invoice);

            long claimId = submitted(line, invoice, "2500.00");

            assertThat(events("CreditClaimSubmitted", invoice)).isEqualTo(1);
            var payload = jdbc.queryForObject("select payload from outbox_event where event_type = "
                    + "'CreditClaimSubmitted' and aggregate_id = ?", String.class, invoice);
            var p = json.readTree(payload);
            assertThat(p.get("claimId").asLong()).isEqualTo(claimId);
            assertThat(p.get("creditAgreementId").asLong()).isEqualTo(line.agreementId());
            assertThat(p.get("outletId").asLong()).isEqualTo(line.buyer().outletId());
            assertThat(p.get("supplierStoreId").asLong()).isEqualTo(line.seller().storeId());
            assertThat(p.get("restaurantName").asText()).isEqualTo("Paradise");
            assertThat(p.get("invoiceNumber").asText()).isEqualTo(number);
            relayEvents("CreditClaimSubmitted", invoice);
            var sent = notificationsFor("CreditClaimSubmitted", invoice);
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).get("audience")).isEqualTo("SUPPLIER_STORE");
            assertThat((String) sent.get(0).get("title")).isNotBlank();
            assertThat((String) sent.get(0).get("body")).contains("2,500").contains(number);
            assertThat((Boolean) sent.get(0).get("critical")).isFalse();
            assertThat(deliveries("CreditClaimSubmitted", invoice, "PUSH")).isEqualTo(1);
            assertThat(deliveries("CreditClaimSubmitted", invoice, "SMS")).isZero();
            // The restaurant is not told about its own claim.
            assertThat(jdbc.queryForObject("select count(*) from notification where event_type = "
                    + "'CreditClaimSubmitted' and audience = 'OUTLET' and target_id = ?", Integer.class, invoice))
                    .isZero();
        }
    }

    // ── confirming ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("the supplier confirms")
    class Confirming {

        @Test
        @DisplayName("confirmReducesOnce: invoice, exposure, one CLAIM_CONFIRMED payment, and a second confirm adds none")
        void confirmReducesOnce() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");
            String key = UUID.randomUUID().toString();

            var reply = confirm(line.seller().token(), claimId, key, null);

            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            assertThat(reply.data().get("status").asText()).isEqualTo("CONFIRMED");
            assertThat(reply.data().get("confirmedAmount").decimalValue()).isEqualByComparingTo("2500.00");
            assertThat(paid(invoice)).isEqualByComparingTo("2500.00");
            assertThat(invoiceStatus(invoice)).isEqualTo("PARTIALLY_PAID");
            assertThat(utilized(line)).isEqualByComparingTo("4000.00");
            var rows = payments(invoice);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("source")).isEqualTo("CLAIM_CONFIRMED");
            assertThat(((Number) rows.get(0).get("claim_id")).longValue()).isEqualTo(claimId);
            assertThat(rows.get(0).get("method")).isEqualTo("UPI");
            assertThat(rows.get(0).get("reference")).isEqualTo(claimRow(claimId).get("reference"));
            var row = claimRow(claimId);
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(((Number) row.get("credit_payment_id")).longValue())
                    .isEqualTo(((Number) rows.get(0).get("id")).longValue());
            assertThat((BigDecimal) row.get("confirmed_amount")).isEqualByComparingTo("2500.00");
            assertThat(row.get("decided_by")).isNotNull();
            assertThat(row.get("decided_at")).isNotNull();

            // Same key again: same response. A new key: refused as a state conflict. Either way one payment.
            var replay = confirm(line.seller().token(), claimId, key, null);
            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.data()).isEqualTo(reply.data());
            var again = confirm(line.seller().token(), claimId, UUID.randomUUID().toString(), null);
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(payments(invoice)).hasSize(1);
            assertThat(paid(invoice)).isEqualByComparingTo("2500.00");
            assertThat(utilized(line)).isEqualByComparingTo("4000.00");
            // The supplier's recording of a payment is not published a second time for a confirm.
            assertThat(events("CreditRepaymentRecorded", invoice)).isZero();
            assertThat(events("CreditClaimConfirmed", invoice)).isEqualTo(1);
        }

        @Test
        @DisplayName("confirming the whole outstanding settles the invoice")
        void confirmSettles() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "6500.00");

            var reply = confirm(line, claimId, null);

            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
            assertThat(utilized(line)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("confirmDefaultsToMinOfClaimAndOutstanding: part paid meanwhile, the confirm caps at what is left")
        void confirmDefaultsToMinOfClaimAndOutstanding() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "5000.00");
            assertThat(s.recordPayment(line.seller(), invoice, "3000.00").status()).isEqualTo(200);

            var reply = confirm(line, claimId, null);

            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            assertThat(reply.data().get("confirmedAmount").decimalValue()).isEqualByComparingTo("3500.00");
            assertThat(paid(invoice)).isEqualByComparingTo("6500.00");
            assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
            assertThat(utilized(line)).isEqualByComparingTo("0");
            assertThat(claimRow(claimId).get("status")).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("partialConfirmAmount: the supplier may confirm less than claimed")
        void partialConfirmAmount() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "5000.00");

            var reply = confirm(line, claimId, "1200.50");

            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            assertThat(reply.data().get("amount").decimalValue()).isEqualByComparingTo("5000.00");
            assertThat(reply.data().get("confirmedAmount").decimalValue()).isEqualByComparingTo("1200.50");
            assertThat(paid(invoice)).isEqualByComparingTo("1200.50");
            assertThat(utilized(line)).isEqualByComparingTo("5299.50");
            assertThat(payments(invoice).get(0).get("amount")).isEqualTo(new BigDecimal("1200.5000"));
        }

        @Test
        @DisplayName("confirmMoreThanClaimRefused, and more than is outstanding now: CREDIT_OVERPAYMENT, nothing moves")
        void confirmMoreThanClaimRefused() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2000.00");
            var before = s.snapshot(line);

            var tooMuch = confirm(line, claimId, "2000.01");
            assertThat(tooMuch.status()).isEqualTo(422);
            assertThat(tooMuch.code()).isEqualTo("CREDIT_OVERPAYMENT");
            for (String bad : List.of("0", "-1.00", "10.001")) {
                var reply = confirm(line, claimId, bad);
                assertThat(reply.status()).as(bad).isEqualTo(400);
                assertThat(reply.code()).as(bad).isEqualTo("VALIDATION_ERROR");
            }

            // Part paid meanwhile: 6500 - 5000 = 1500 left, so confirming 1600 of a 2000 claim is refused.
            assertThat(s.recordPayment(line.seller(), invoice, "5000.00").status()).isEqualTo(200);
            var afterPart = s.snapshot(line);
            var overOutstanding = confirm(line, claimId, "1600.00");
            assertThat(overOutstanding.status()).isEqualTo(422);
            assertThat(overOutstanding.code()).isEqualTo("CREDIT_OVERPAYMENT");
            assertThat(overOutstanding.body().at("/error/details/outstanding").decimalValue())
                    .isEqualByComparingTo("1500.00");
            assertThat(before.creditPayments() + 1).isEqualTo(afterPart.creditPayments());
            assertThat(s.snapshot(line).invoices()).isEqualTo(afterPart.invoices());
            assertThat(claimRow(claimId).get("status")).isEqualTo("SUBMITTED");
            assertThat(payments(invoice)).hasSize(1);
        }

        @Test
        @DisplayName("an invoice already settled cannot take the confirm: CREDIT_CLAIM_STATE, claim stays submitted")
        void confirmAfterInvoiceSettled() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "1000.00");
            assertThat(s.recordPayment(line.seller(), invoice, "6500.00").status()).isEqualTo(200);

            var reply = confirm(line, claimId, null);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(reply.body().at("/error/message").asText()).isNotBlank();
            assertThat(claimRow(claimId).get("status")).isEqualTo("SUBMITTED");
            assertThat(payments(invoice)).hasSize(1);
        }

        @Test
        @DisplayName("confirm needs an Idempotency-Key of 1 to 100 characters")
        void confirmNeedsKey() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "1000.00");

            assertThat(confirm(line.seller().token(), claimId, null, null).status()).isEqualTo(400);
            assertThat(confirm(line.seller().token(), claimId, "k".repeat(101), null).status()).isEqualTo(400);
            assertThat(claimRow(claimId).get("status")).isEqualTo("SUBMITTED");
            assertThat(payments(invoice)).isEmpty();
        }

        @Test
        @DisplayName("confirming on a system-suspended line reinstates it once the overdue is within the maximum")
        void confirmReinstates() throws Exception {
            var line = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
            long invoice = s.invoice(line, "400", 100);
            s.age(invoice, 10);
            creditJobs.sweepOverdue();
            assertThat(agreementStatus(line)).isEqualTo("SUSPENDED");
            long claimId = submitted(line, invoice, "40000.00");
            assertThat(agreementStatus(line)).isEqualTo("SUSPENDED");

            assertThat(confirm(line, claimId, "35000.00").status()).isEqualTo(200);

            assertThat(agreementStatus(line)).isEqualTo("ACTIVE");
            assertThat(events("CreditReinstated", line.agreementId())).isEqualTo(1);
        }

        @Test
        @DisplayName("the restaurant is told on confirm, in-app and push, never SMS")
        void restaurantToldOnConfirm() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            registerDevice(line.buyer().token());
            long claimId = submitted(line, invoice, "2500.00");
            assertThat(confirm(line, claimId, "2000.00").status()).isEqualTo(200);

            var payload = json.readTree(jdbc.queryForObject("select payload from outbox_event where event_type = "
                    + "'CreditClaimConfirmed' and aggregate_id = ?", String.class, invoice));
            assertThat(payload.get("claimId").asLong()).isEqualTo(claimId);
            assertThat(payload.get("outletId").asLong()).isEqualTo(line.buyer().outletId());
            assertThat(payload.get("supplierName").asText()).isEqualTo("ABC Foods");
            relayEvents("CreditClaimConfirmed", invoice);

            var sent = notificationsFor("CreditClaimConfirmed", invoice);
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).get("audience")).isEqualTo("OUTLET");
            assertThat((String) sent.get(0).get("body")).contains("ABC Foods").contains("2,000");
            assertThat((Boolean) sent.get(0).get("critical")).isFalse();
            assertThat(deliveries("CreditClaimConfirmed", invoice, "PUSH")).isEqualTo(1);
            assertThat(deliveries("CreditClaimConfirmed", invoice, "SMS")).isZero();
        }
    }

    // ── rejecting and withdrawing ────────────────────────────────────────

    @Nested
    @DisplayName("rejecting and withdrawing")
    class RejectingAndWithdrawing {

        @Test
        @DisplayName("rejectNotifiesWithReason: restaurant in-app and push, no SMS, and no money effect")
        void rejectNotifiesWithReason() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            registerDevice(line.buyer().token());
            long claimId = submitted(line, invoice, "2500.00");
            var before = s.snapshot(line);

            var reply = reject(line.seller().token(), claimId, "No such payment in our bank statement.");

            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            assertThat(reply.data().get("status").asText()).isEqualTo("REJECTED");
            assertThat(reply.data().get("decisionNote").asText()).contains("bank statement");
            var after = s.snapshot(line);
            assertThat(after.invoices()).isEqualTo(before.invoices());
            assertThat(after.utilized()).isEqualByComparingTo(before.utilized());
            assertThat(after.creditPayments()).isEqualTo(before.creditPayments());
            assertThat(after.ledgerRows()).isEqualTo(before.ledgerRows());
            var row = claimRow(claimId);
            assertThat(row.get("status")).isEqualTo("REJECTED");
            assertThat(row.get("credit_payment_id")).isNull();
            assertThat(row.get("confirmed_amount")).isNull();
            assertThat(row.get("decided_by")).isNotNull();

            relayEvents("CreditClaimRejected", invoice);
            var sent = notificationsFor("CreditClaimRejected", invoice);
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).get("audience")).isEqualTo("OUTLET");
            assertThat((String) sent.get(0).get("body")).contains("ABC Foods").contains("2,500")
                    .contains("No such payment in our bank statement.");
            assertThat(deliveries("CreditClaimRejected", invoice, "PUSH")).isEqualTo(1);
            assertThat(deliveries("CreditClaimRejected", invoice, "SMS")).isZero();

            // A rejected claim can no longer be confirmed, rejected or withdrawn; it no longer counts as open.
            assertThat(confirm(line, claimId, null).code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(reject(line.seller().token(), claimId, "Again, no.").code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(withdraw(line.buyer().token(), claimId).code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(payments(invoice)).isEmpty();
            var agreement = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId());
            assertThat(agreement.at("/data/openClaimsAmount").decimalValue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a rejection needs a reason of 3 to 500 characters")
        void rejectNeedsReason() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");

            for (String reason : List.of("", "  ", "no", "x".repeat(501))) {
                var reply = reject(line.seller().token(), claimId, reason);
                assertThat(reply.status()).as(reason.length() + " chars").isEqualTo(400);
                assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
            }
            assertThat(claimRow(claimId).get("status")).isEqualTo("SUBMITTED");
        }

        @Test
        @DisplayName("withdrawing a submitted claim frees what it held, and changes no money")
        void withdrawFreesWhatItHeld() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "3900.00");
            var before = s.snapshot(line);

            var reply = withdraw(line.buyer().token(), claimId);

            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            assertThat(reply.data().get("status").asText()).isEqualTo("WITHDRAWN");
            assertThat(claimRow(claimId).get("status")).isEqualTo("WITHDRAWN");
            var after = s.snapshot(line);
            assertThat(after.invoices()).isEqualTo(before.invoices());
            assertThat(after.creditPayments()).isEqualTo(before.creditPayments());
            // The room it held is free again.
            assertThat(claim(line, invoice, "6500.00").status()).isEqualTo(201);
            // Withdrawing again is the same final state, so a retry after a lost response is answered, not refused
            // (D-129); a claim in any OTHER state still is (confirmedCannotBeWithdrawn).
            var twice = withdraw(line.buyer().token(), claimId);
            assertThat(twice.status()).isEqualTo(200);
            assertThat(twice.data().get("status").asText()).isEqualTo("WITHDRAWN");
        }

        @Test
        @DisplayName("withdrawnClaimCannotBeConfirmed: CREDIT_CLAIM_STATE, no payment")
        void withdrawnClaimCannotBeConfirmed() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");
            assertThat(withdraw(line.buyer().token(), claimId).status()).isEqualTo(200);

            var reply = confirm(line, claimId, null);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(payments(invoice)).isEmpty();
            assertThat(paid(invoice)).isEqualByComparingTo("0");
            assertThat(utilized(line)).isEqualByComparingTo("6500.00");
            assertThat(claimRow(claimId).get("status")).isEqualTo("WITHDRAWN");
        }

        @Test
        @DisplayName("a confirmed claim cannot be withdrawn: the money is already counted")
        void confirmedCannotBeWithdrawn() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");
            assertThat(confirm(line, claimId, null).status()).isEqualTo(200);

            var reply = withdraw(line.buyer().token(), claimId);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(claimRow(claimId).get("status")).isEqualTo("CONFIRMED");
            assertThat(paid(invoice)).isEqualByComparingTo("2500.00");
        }
    }

    // ── creating: rules ──────────────────────────────────────────────────

    @Nested
    @DisplayName("what a claim may say")
    class CreatingRules {

        @Test
        @DisplayName("claimsCannotExceedOutstanding: two claims of 60% on one invoice, the second is refused")
        void claimsCannotExceedOutstanding() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            assertThat(claim(line, invoice, "3900.00").status()).isEqualTo(201);

            var second = claim(line, invoice, "3900.00");

            assertThat(second.status()).isEqualTo(422);
            assertThat(second.code()).isEqualTo("CREDIT_OVERPAYMENT");
            assertThat(second.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("2600.00");
            assertThat(claimCount(invoice)).isEqualTo(1);
            assertThat(events("CreditClaimSubmitted", invoice)).isEqualTo(1);
            // What is left can still be claimed.
            assertThat(claim(line, invoice, "2600.00").status()).isEqualTo(201);
            assertThat(claimCount(invoice)).isEqualTo(2);
            // And the part-paid invoice is counted at what is outstanding, not at its face value.
            assertThat(s.recordPayment(line.seller(), invoice, "1000.00").status()).isEqualTo(200);
            assertThat(claim(line, invoice, "1.00").status()).isEqualTo(422);
        }

        @Test
        @DisplayName("claimOnPaidInvoiceRefused: nothing is outstanding, nothing is created")
        void claimOnPaidInvoiceRefused() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            assertThat(s.recordPayment(line.seller(), invoice, "6500.00").status()).isEqualTo(200);

            var reply = claim(line, invoice, "100.00");

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.code()).isEqualTo("CREDIT_OVERPAYMENT");
            assertThat(reply.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("0");
            assertThat(claimCount(invoice)).isZero();
        }

        @Test
        @DisplayName("futurePaidOnRefused, and a date before the invoice was issued; today is fine")
        void paidOnRules() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            String token = line.buyer().token();

            var future = body("100.00");
            future.put("paidOn", today().plusDays(1).toString());
            var reply = claim(token, invoice, UUID.randomUUID().toString(), future);
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");

            var early = body("100.00");
            early.put("paidOn", today().minusDays(1).toString());
            var tooEarly = claim(token, invoice, UUID.randomUUID().toString(), early);
            assertThat(tooEarly.status()).isEqualTo(400);
            assertThat(tooEarly.code()).isEqualTo("VALIDATION_ERROR");

            var missing = body("100.00");
            missing.remove("paidOn");
            assertThat(claim(token, invoice, UUID.randomUUID().toString(), missing).status()).isEqualTo(400);

            assertThat(claimCount(invoice)).isZero();
            assertThat(claim(token, invoice, UUID.randomUUID().toString(), body("100.00")).status()).isEqualTo(201);
        }

        @Test
        @DisplayName("referenceRequiredUnlessCash")
        void referenceRequiredUnlessCash() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            String token = line.buyer().token();

            for (String method : List.of("BANK_TRANSFER", "UPI", "CHEQUE", "CARD")) {
                var b = body("100.00");
                b.put("method", method);
                b.remove("reference");
                var reply = claim(token, invoice, UUID.randomUUID().toString(), b);
                assertThat(reply.status()).as(method).isEqualTo(400);
                assertThat(reply.code()).as(method).isEqualTo("VALIDATION_ERROR");
                b.put("reference", "  ");
                assertThat(claim(token, invoice, UUID.randomUUID().toString(), b).status()).as(method + " blank")
                        .isEqualTo(400);
            }
            assertThat(claimCount(invoice)).isZero();

            var cash = body("100.00");
            cash.put("method", "CASH");
            cash.remove("reference");
            var reply = claim(token, invoice, UUID.randomUUID().toString(), cash);
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
            assertThat(reply.data().get("method").asText()).isEqualTo("CASH");
            assertThat(reply.data().get("reference").isNull()).isTrue();
        }

        @Test
        @DisplayName("amount at least 1.00 with at most two decimals; method one of the five; fields bounded")
        void bodyValidation() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            String token = line.buyer().token();

            for (String amount : List.of("0.99", "0", "-5.00", "10.001")) {
                var reply = claim(token, invoice, UUID.randomUUID().toString(), body(amount));
                assertThat(reply.status()).as(amount).isEqualTo(400);
                assertThat(reply.code()).as(amount).isEqualTo("VALIDATION_ERROR");
            }
            for (String method : List.of("ADJUSTMENT", "WALLET", "NEFT", "upi")) {
                var b = body("100.00");
                b.put("method", method);
                var reply = claim(token, invoice, UUID.randomUUID().toString(), b);
                assertThat(reply.status()).as(method).isEqualTo(400);
            }
            var longRef = body("100.00");
            longRef.put("reference", "r".repeat(201));
            assertThat(claim(token, invoice, UUID.randomUUID().toString(), longRef).status()).isEqualTo(400);
            var longNote = body("100.00");
            longNote.put("note", "n".repeat(501));
            assertThat(claim(token, invoice, UUID.randomUUID().toString(), longNote).status()).isEqualTo(400);
            assertThat(claim(token, invoice, null, body("100.00")).status()).isEqualTo(400);
            assertThat(claim(token, invoice, "k".repeat(101), body("100.00")).status()).isEqualTo(400);
            assertThat(claimCount(invoice)).isZero();

            var ok = body("100.00");
            ok.put("note", "Paid at the counter");
            var reply = claim(token, invoice, UUID.randomUUID().toString(), ok);
            assertThat(reply.status()).isEqualTo(201);
            var d = reply.data();
            assertThat(d.get("invoiceId").asLong()).isEqualTo(invoice);
            assertThat(d.get("agreementId").asLong()).isEqualTo(line.agreementId());
            assertThat(d.get("invoiceNumber").asText()).isNotBlank();
            assertThat(d.get("note").asText()).isEqualTo("Paid at the counter");
            assertThat(d.get("paidOn").asText()).isEqualTo(today().toString());
            assertThat(d.get("createdAt").isNull()).isFalse();
            assertThat(d.get("decidedAt").isNull()).isTrue();
            assertThat(d.get("confirmedAmount").isNull()).isTrue();
        }

        @Test
        @DisplayName("sameKeyTwiceOneClaim")
        void sameKeyTwiceOneClaim() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            String key = UUID.randomUUID().toString();
            var b = body("2500.00");

            var first = claim(line.buyer().token(), invoice, key, b);
            var second = claim(line.buyer().token(), invoice, key, b);

            assertThat(first.status()).isEqualTo(201);
            assertThat(second.status()).isEqualTo(first.status());
            assertThat(second.data()).isEqualTo(first.data());
            assertThat(claimCount(invoice)).isEqualTo(1);
            assertThat(events("CreditClaimSubmitted", invoice)).isEqualTo(1);
        }

        @Test
        @DisplayName("sameKeyDifferentPayloadRejected")
        void sameKeyDifferentPayloadRejected() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            String key = UUID.randomUUID().toString();
            assertThat(claim(line.buyer().token(), invoice, key, body("2500.00")).status()).isEqualTo(201);

            var again = claim(line.buyer().token(), invoice, key, body("100.00"));

            assertThat(again.status()).isEqualTo(409);
            assertThat(again.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
            assertThat(claimCount(invoice)).isEqualTo(1);
        }
    }

    // ── who may do what ──────────────────────────────────────────────────

    @Nested
    @DisplayName("tenants and roles")
    class Isolation {

        @Test
        @DisplayName("restaurantCannotConfirm: a restaurant user gets 404 on confirm and reject, and nothing moves")
        void restaurantCannotConfirm() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");

            var confirmed = confirm(line.buyer().token(), claimId, UUID.randomUUID().toString(), null);
            var rejected = reject(line.buyer().token(), claimId, "I changed my mind");

            assertThat(confirmed.status()).isEqualTo(404);
            assertThat(rejected.status()).isEqualTo(404);
            assertThat(payments(invoice)).isEmpty();
            assertThat(paid(invoice)).isEqualByComparingTo("0");
            assertThat(utilized(line)).isEqualByComparingTo("6500.00");
            assertThat(claimRow(claimId).get("status")).isEqualTo("SUBMITTED");
        }

        @Test
        @DisplayName("supplierCannotCreateClaim: a supplier user gets 404 on create and withdraw")
        void supplierCannotCreateClaim() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);

            var created = claim(line.seller().token(), invoice, UUID.randomUUID().toString(), body("100.00"));
            assertThat(created.status()).isEqualTo(404);
            assertThat(claimCount(invoice)).isZero();

            long claimId = submitted(line, invoice, "100.00");
            assertThat(withdraw(line.seller().token(), claimId).status()).isEqualTo(404);
            assertThat(claimRow(claimId).get("status")).isEqualTo("SUBMITTED");
        }

        @Test
        @DisplayName("otherOutletAndOtherStoreIsolation: both directions, reads too")
        void otherOutletAndOtherStoreIsolation() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");
            Buyer otherBuyer = s.newBuyer();
            var otherSeller = s.newSeller();
            String store = "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/claims";
            String agreementClaims = "/api/v1/credit/agreements/" + line.agreementId() + "/claims";

            // Another restaurant: cannot claim on, withdraw, or read this invoice's claims.
            assertThat(claim(otherBuyer.token(), invoice, UUID.randomUUID().toString(), body("100.00")).status())
                    .isEqualTo(404);
            assertThat(withdraw(otherBuyer.token(), claimId).status()).isEqualTo(404);
            assertThat(call("GET", otherBuyer.token(), agreementClaims, null, null).status()).isEqualTo(404);
            assertThat(call("GET", otherBuyer.token(), store + "?status=SUBMITTED", null, null).status())
                    .isEqualTo(404);
            assertThat(call("GET", otherBuyer.token(), "/api/v1/credit/invoices/" + invoice, null, null).status())
                    .isEqualTo(404);

            // Another supplier: cannot confirm, reject, or read this store's claims.
            assertThat(confirm(otherSeller.token(), claimId, UUID.randomUUID().toString(), null).status())
                    .isEqualTo(404);
            assertThat(reject(otherSeller.token(), claimId, "Not mine").status()).isEqualTo(404);
            assertThat(call("GET", otherSeller.token(), agreementClaims, null, null).status()).isEqualTo(404);
            assertThat(call("GET", otherSeller.token(), store + "?status=SUBMITTED", null, null).status())
                    .isEqualTo(404);
            assertThat(call("GET", otherSeller.token(), "/api/v1/credit/invoices/" + invoice, null, null).status())
                    .isEqualTo(404);

            // A restaurant user reading the supplier's inbox, and a supplier reading an outlet's: 404 too.
            assertThat(call("GET", line.buyer().token(), store + "?status=SUBMITTED", null, null).status())
                    .isEqualTo(404);

            // Nothing moved, and the owners can still read.
            assertThat(claimRow(claimId).get("status")).isEqualTo("SUBMITTED");
            assertThat(payments(invoice)).isEmpty();
            assertThat(call("GET", line.buyer().token(), agreementClaims, null, null).status()).isEqualTo(200);
            assertThat(call("GET", line.seller().token(), agreementClaims, null, null).status()).isEqualTo(200);
            assertThat(call("GET", line.seller().token(), store + "?status=SUBMITTED", null, null).status())
                    .isEqualTo(200);
        }
    }

    // ── reads ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("the supplier inbox lists only that store's SUBMITTED claims, with names and the invoice number")
        void supplierInbox() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long invoice2 = s.invoice(line, "40", 100);
            long first = submitted(line, invoice, "1000.00");
            long second = submitted(line, invoice2, "500.00");
            long third = submitted(line, invoice2, "200.00");
            assertThat(confirm(line, third, null).status()).isEqualTo(200);
            assertThat(withdraw(line.buyer().token(), second).status()).isEqualTo(200);
            // Another store's claim must not appear.
            var other = s.creditLine("200000");
            long otherInvoice = s.invoice(other, "65", 100);
            long otherClaim = submitted(other, otherInvoice, "700.00");

            var inbox = call("GET", line.seller().token(),
                    "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/claims?status=SUBMITTED",
                    null, null);

            assertThat(inbox.status()).describedAs(inbox.body().toString()).isEqualTo(200);
            assertThat(inbox.data()).hasSize(1);
            var row = inbox.data().get(0);
            assertThat(row.get("id").asLong()).isEqualTo(first);
            assertThat(row.get("restaurantName").asText()).isEqualTo("Paradise");
            assertThat(row.get("outletName").asText()).isEqualTo("Banjara Hills");
            assertThat(row.get("invoiceNumber").asText()).isNotBlank();
            assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("1000.00");
            assertThat(inbox.body().toString()).doesNotContain("\"id\":" + otherClaim + ",");

            var all = call("GET", line.seller().token(),
                    "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/claims", null, null);
            assertThat(all.data()).hasSize(3);
            var bad = call("GET", line.seller().token(),
                    "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/claims?status=NOPE", null, null);
            assertThat(bad.status()).isEqualTo(400);
        }

        @Test
        @DisplayName("an agreement's claims read newest first, filterable by status, for either side")
        void agreementClaims() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long a = submitted(line, invoice, "1000.00");
            long b = submitted(line, invoice, "500.00");
            assertThat(reject(line.seller().token(), a, "Not received")).isNotNull();

            for (String token : List.of(line.buyer().token(), line.seller().token())) {
                var all = call("GET", token, "/api/v1/credit/agreements/" + line.agreementId() + "/claims",
                        null, null);
                assertThat(all.status()).isEqualTo(200);
                assertThat(all.data()).hasSize(2);
                assertThat(all.data().get(0).get("id").asLong()).isEqualTo(b);
                assertThat(all.data().get(1).get("id").asLong()).isEqualTo(a);
                var rejected = call("GET", token, "/api/v1/credit/agreements/" + line.agreementId()
                        + "/claims?status=REJECTED", null, null);
                assertThat(rejected.data()).hasSize(1);
                assertThat(rejected.data().get(0).get("decisionNote").asText()).isEqualTo("Not received");
                assertThat(rejected.data().get(0).get("decidedAt").isNull()).isFalse();
            }
        }

        @Test
        @DisplayName("the invoice detail lists its claims, and the agreement and summary carry openClaimsAmount")
        void invoiceDetailAndOpenClaims() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            var none = s.api.get(line.buyer().token(), "/api/v1/credit/invoices/" + invoice);
            assertThat(none.at("/data/claims")).isEmpty();
            assertThat(none.at("/data/claims").isArray()).isTrue();
            long a = submitted(line, invoice, "1000.00");
            long b = submitted(line, invoice, "500.00");
            assertThat(withdraw(line.buyer().token(), b).status()).isEqualTo(200);

            var detail = s.api.get(line.seller().token(), "/api/v1/credit/invoices/" + invoice);
            var claims = detail.at("/data/claims");
            assertThat(claims).hasSize(2);
            assertThat(claims.get(0).get("id").asLong()).isEqualTo(b);
            assertThat(claims.get(1).get("id").asLong()).isEqualTo(a);
            assertThat(claims.get(1).get("status").asText()).isEqualTo("SUBMITTED");

            var agreement = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId());
            assertThat(agreement.at("/data/openClaimsAmount").decimalValue()).isEqualByComparingTo("1000.00");
            var summary = s.api.get(line.buyer().token(), "/api/v1/outlets/" + line.buyer().outletId()
                    + "/credit/summary");
            assertThat(summary.at("/data/agreements/0/openClaimsAmount").decimalValue())
                    .isEqualByComparingTo("1000.00");
            assertThat(summary.at("/data/openClaimsAmount").decimalValue()).isEqualByComparingTo("1000.00");
            var forStore = s.api.get(line.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId()
                    + "/credit/agreements");
            assertThat(forStore.at("/data/0/openClaimsAmount").decimalValue()).isEqualByComparingTo("1000.00");
            assertThat(confirm(line, a, null).status()).isEqualTo(200);
            assertThat(s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId())
                    .at("/data/openClaimsAmount").decimalValue()).isEqualByComparingTo("0");
        }
    }

    // ── how much can still be reported (D-127) ───────────────────────────

    @Nested
    @DisplayName("reportableAmount: the server says how much can still be reported")
    class ReportableAmount {

        private BigDecimal detail(Line line, long invoice) throws Exception {
            var node = s.api.get(line.buyer().token(), "/api/v1/credit/invoices/" + invoice);
            assertThat(node.at("/data/reportableAmount").isMissingNode()).isFalse();
            return node.at("/data/reportableAmount").decimalValue();
        }

        private BigDecimal listed(Line line, long invoice) throws Exception {
            var list = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/invoices");
            for (var row : list.at("/data")) {
                if (row.get("id").asLong() == invoice) {
                    assertThat(row.get("reportableAmount").isMissingNode()).isFalse();
                    return row.get("reportableAmount").decimalValue();
                }
            }
            throw new AssertionError("invoice " + invoice + " not listed");
        }

        private void assertInvoice(Line line, long invoice, String expected) throws Exception {
            assertThat(detail(line, invoice)).isEqualByComparingTo(expected);
            assertThat(listed(line, invoice)).isEqualByComparingTo(expected);
        }

        private BigDecimal agreementTotal(Line line) throws Exception {
            var agreement = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId());
            return agreement.at("/data/reportableAmount").decimalValue();
        }

        @Test
        @DisplayName("noClaims: reportable equals outstanding")
        void noClaims() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            assertInvoice(line, invoice, "6500.00");
            assertThat(agreementTotal(line)).isEqualByComparingTo("6500.00");
        }

        @Test
        @DisplayName("sixtyPercentClaim: 60 percent submitted leaves 40 percent reportable, outstanding unchanged")
        void sixtyPercentClaim() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            submitted(line, invoice, "3900.00");
            assertInvoice(line, invoice, "2600.00");
            assertThat(s.api.get(line.buyer().token(), "/api/v1/credit/invoices/" + invoice)
                    .at("/data/outstanding").decimalValue()).isEqualByComparingTo("6500.00");
        }

        @Test
        @DisplayName("withdrawAndReject: both give the amount back")
        void withdrawAndReject() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long a = submitted(line, invoice, "3900.00");
            assertInvoice(line, invoice, "2600.00");
            assertThat(withdraw(line.buyer().token(), a).status()).isEqualTo(200);
            assertInvoice(line, invoice, "6500.00");
            long b = submitted(line, invoice, "1000.00");
            assertInvoice(line, invoice, "5500.00");
            assertThat(reject(line.seller().token(), b, "Not received").status()).isEqualTo(200);
            assertInvoice(line, invoice, "6500.00");
        }

        @Test
        @DisplayName("confirmPart: outstanding drops, reportable is the new outstanding minus the claims still open")
        void confirmPart() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long a = submitted(line, invoice, "2000.00");
            submitted(line, invoice, "1000.00");
            assertInvoice(line, invoice, "3500.00");

            assertThat(confirm(line, a, "1500.00").status()).isEqualTo(200);

            // outstanding 5000, the 1000 claim is still open
            assertThat(s.api.get(line.buyer().token(), "/api/v1/credit/invoices/" + invoice)
                    .at("/data/outstanding").decimalValue()).isEqualByComparingTo("5000.00");
            assertInvoice(line, invoice, "4000.00");
        }

        @Test
        @DisplayName("settledInvoice: reportable is 0")
        void settledInvoice() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long a = submitted(line, invoice, "6500.00");
            assertInvoice(line, invoice, "0.00");
            assertThat(confirm(line, a, null).status()).isEqualTo(200);
            assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
            assertInvoice(line, invoice, "0");
            assertThat(agreementTotal(line)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("twoClaimsAndOtherInvoice: claims add up and never leak onto another invoice")
        void twoClaimsAndOtherInvoice() throws Exception {
            var line = s.creditLine("200000");
            long first = s.invoice(line, "65", 100);
            long second = s.invoice(line, "20", 100);
            submitted(line, first, "1000.00");
            submitted(line, first, "500.00");
            assertInvoice(line, first, "5000.00");
            assertInvoice(line, second, "2000.00");
            submitted(line, second, "250.00");
            assertInvoice(line, first, "5000.00");
            assertInvoice(line, second, "1750.00");
        }

        @Test
        @DisplayName("agreementAndSummaryTotals: sums per invoice at agreement, summary entry and summary level")
        void agreementAndSummaryTotals() throws Exception {
            var line = s.creditLine("200000");
            long first = s.invoice(line, "65", 100);
            long second = s.invoice(line, "20", 100);
            submitted(line, first, "1000.00");
            submitted(line, second, "250.00");
            // 8500 owed, 1250 reported
            assertThat(agreementTotal(line)).isEqualByComparingTo("7250.00");
            var summary = s.api.get(line.buyer().token(), "/api/v1/outlets/" + line.buyer().outletId()
                    + "/credit/summary");
            assertThat(summary.at("/data/agreements/0/reportableAmount").decimalValue())
                    .isEqualByComparingTo("7250.00");
            assertThat(summary.at("/data/reportableAmount").decimalValue()).isEqualByComparingTo("7250.00");
            var forStore = s.api.get(line.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId()
                    + "/credit/agreements");
            assertThat(forStore.at("/data/0/reportableAmount").decimalValue()).isEqualByComparingTo("7250.00");
            var supplierSide = s.api.get(line.seller().token(), "/api/v1/credit/invoices/" + first);
            assertThat(supplierSide.at("/data/reportableAmount").decimalValue()).isEqualByComparingTo("5500.00");
        }

        @Test
        @DisplayName("exactlyReportable: claiming reportableAmount works, one paisa more is refused with the same number")
        void exactlyReportable() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            submitted(line, invoice, "3900.00");
            BigDecimal reportable = detail(line, invoice);
            assertThat(reportable).isEqualByComparingTo("2600.00");

            var over = claim(line, invoice, reportable.add(new BigDecimal("0.01")).toPlainString());
            assertThat(over.status()).isEqualTo(422);
            assertThat(over.code()).isEqualTo("CREDIT_OVERPAYMENT");
            assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo(reportable);

            assertThat(claim(line, invoice, reportable.toPlainString()).status()).isEqualTo(201);
            assertInvoice(line, invoice, "0.00");
            assertThat(claim(line, invoice, "0.01").body().at("/error/details/outstanding").decimalValue())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("overClaimed: claims above a reduced outstanding floor at 0, never negative")
        void overClaimed() throws Exception {
            var line = s.creditLine("200000");
            long first = s.invoice(line, "65", 100);
            long second = s.invoice(line, "20", 100);
            submitted(line, first, "4000.00");
            // A payment the supplier recorded straight after: outstanding 1500 while 4000 is still claimed.
            jdbc.update("update credit_invoice set paid_amount = 5000.00 where id = ?", first);
            assertInvoice(line, first, "0");
            // The over-claimed invoice does not eat into the other one.
            assertInvoice(line, second, "2000.00");
            assertThat(agreementTotal(line)).isEqualByComparingTo("2000.00");
        }

        @Test
        @DisplayName("otherTenant: another restaurant still cannot read the invoice or its figure")
        void otherTenant() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            submitted(line, invoice, "1000.00");
            Buyer other = s.newBuyer();
            assertThat(call("GET", other.token(), "/api/v1/credit/invoices/" + invoice, null, null).status())
                    .isEqualTo(404);
        }
    }

    // ── concurrency and locks ────────────────────────────────────────────

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("two confirms of one claim at once: exactly one payment")
        void concurrentConfirmsOnePayment() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");

            var start = new CountDownLatch(1);
            List<Future<Reply>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return confirm(line, claimId, null);
                }));
            }
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (var call : calls) {
                replies.add(call.get(60, TimeUnit.SECONDS));
            }

            assertThat(replies.stream().map(Reply::status)).containsExactlyInAnyOrder(200, 409);
            assertThat(replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().code())
                    .isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(payments(invoice)).hasSize(1);
            assertThat(paid(invoice)).isEqualByComparingTo("2500.00");
            assertThat(utilized(line)).isEqualByComparingTo("4000.00");
            assertThat(events("CreditClaimConfirmed", invoice)).isEqualTo(1);
        }

        @Test
        @DisplayName("a confirm racing a wallet repayment of the same invoice never overpays")
        void confirmRacingWalletRepayment() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            s.topUp(line.buyer(), "20000.00");
            long claimId = submitted(line, invoice, "6500.00");

            var start = new CountDownLatch(1);
            Future<Reply> confirming = pool.submit(() -> {
                start.await();
                return confirm(line, claimId, null);
            });
            Future<Reply> repaying = pool.submit(() -> {
                start.await();
                return s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                        Map.of("amount", "6500.00"));
            });
            start.countDown();
            var c = confirming.get(60, TimeUnit.SECONDS);
            var r = repaying.get(60, TimeUnit.SECONDS);

            // Exactly one of them pays the invoice; the other is refused cleanly, never half applied.
            boolean confirmOk = c.status() == 200;
            boolean repayOk = r.status() == 201;
            assertThat(confirmOk ^ repayOk).describedAs("confirm=%s repay=%s", c.body(), r.body()).isTrue();
            if (!confirmOk) {
                assertThat(c.code()).isEqualTo("CREDIT_CLAIM_STATE");
            } else {
                assertThat(r.code()).isEqualTo("CREDIT_OVERPAYMENT");
            }
            assertThat(paid(invoice)).isEqualByComparingTo("6500.00");
            assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
            assertThat(utilized(line)).isEqualByComparingTo("0");
            assertThat(payments(invoice)).hasSize(1);
        }

        @Test
        @DisplayName("the invoice is locked FOR UPDATE: a change committed while the confirm waited is what it caps at")
        void confirmLocksTheInvoice() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "5000.00");
            var holder = new Holder(
                    "select id from credit_invoice where id = " + invoice + " for update",
                    "update credit_invoice set paid_amount = 3000, status = 'PARTIALLY_PAID', version = version + 1 "
                            + "where id = " + invoice,
                    "update credit_agreement set utilized_amount = utilized_amount - 3000 where id = "
                            + line.agreementId());

            var call = pool.submit(() -> confirm(line, claimId, null));
            Thread.sleep(1500);
            assertThat(call.isDone()).describedAs("the confirm must wait for the invoice").isFalse();
            holder.release();

            var reply = call.get(30, TimeUnit.SECONDS);
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            // 3500 was left, not the stale 6500: a claim of 5000 caps at 3500.
            assertThat(reply.data().get("confirmedAmount").decimalValue()).isEqualByComparingTo("3500.00");
            assertThat(paid(invoice)).isEqualByComparingTo("6500.00");
            assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
            assertThat(utilized(line)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("the claim row is locked too: a withdrawal committed while the confirm waited wins")
        void confirmLocksTheClaim() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "5000.00");
            var holder = new Holder(
                    "select id from credit_payment_claim where id = " + claimId + " for update",
                    "update credit_payment_claim set status = 'WITHDRAWN', version = version + 1 where id = " + claimId);

            var call = pool.submit(() -> confirm(line, claimId, null));
            Thread.sleep(1500);
            assertThat(call.isDone()).describedAs("the confirm must wait for the claim").isFalse();
            holder.release();

            var reply = call.get(30, TimeUnit.SECONDS);
            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("CREDIT_CLAIM_STATE");
            assertThat(payments(invoice)).isEmpty();
            assertThat(paid(invoice)).isEqualByComparingTo("0");
        }
    }

    // ── the supplier's own payment endpoint ──────────────────────────────

    @Nested
    @DisplayName("a payment the supplier records")
    class SupplierRecorded {

        private Reply record(Line line, long invoice, Map<String, Object> body) throws Exception {
            return call("POST", line.seller().token(), "/api/v1/credit/invoices/" + invoice + "/payments",
                    UUID.randomUUID().toString(), body);
        }

        @Test
        @DisplayName("method must be one of BANK_TRANSFER, UPI, CASH, CHEQUE, CARD, ADJUSTMENT")
        void methodIsAnEnum() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);

            for (String method : List.of("NEFT", "bank_transfer", "WALLET", "", "SOMETHING")) {
                var reply = record(line, invoice, Map.of("amount", "100.00", "method", method));
                assertThat(reply.status()).as("'" + method + "'").isEqualTo(400);
                assertThat(reply.code()).as("'" + method + "'").isEqualTo("VALIDATION_ERROR");
            }
            assertThat(payments(invoice)).isEmpty();
            assertThat(paid(invoice)).isEqualByComparingTo("0");

            int recorded = 0;
            for (String method : List.of("BANK_TRANSFER", "UPI", "CASH", "CHEQUE", "CARD", "ADJUSTMENT")) {
                var reply = record(line, invoice, Map.of("amount", "10.00", "method", method));
                assertThat(reply.status()).as(method).isEqualTo(200);
                assertThat(reply.data().get("method").asText()).isEqualTo(method);
                recorded++;
            }
            assertThat(payments(invoice)).hasSize(recorded);
            assertThat(paid(invoice)).isEqualByComparingTo("60.00");
        }

        @Test
        @DisplayName("amount has at most two decimals")
        void amountTwoDecimals() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);

            var reply = record(line, invoice, Map.of("amount", "10.001", "method", "CASH"));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(payments(invoice)).isEmpty();
            assertThat(record(line, invoice, Map.of("amount", "10.05", "method", "CASH")).status()).isEqualTo(200);
        }
    }

    // ── D-129: retries, and keys that belong to one tenant ───────────────

    @Nested
    @DisplayName("idempotency and key safety (D-129)")
    class IdempotencyAndKeySafety {

        private Reply record(Line line, long invoice, String key, Map<String, Object> body) throws Exception {
            return call("POST", line.seller().token(), "/api/v1/credit/invoices/" + invoice + "/payments", key, body);
        }

        private Map<String, Object> pay(String amount) {
            return new HashMap<>(Map.of("amount", amount, "method", "BANK_TRANSFER", "reference", "UTR12345678"));
        }

        private int auditRows(String action, long claimId) {
            return jdbc.queryForObject("select count(*) from audit_log where action = ? and entity_id = ?",
                    Integer.class, action, claimId);
        }

        @Test
        @DisplayName("supplier A sending supplier B's key gets A's own payment, never B's")
        void anotherTenantsKeyLeaksNothing() throws Exception {
            var a = s.creditLine("200000");
            var b = s.creditLine("200000");
            long invoiceA = s.invoice(a, "65", 100);
            long invoiceB = s.invoice(b, "65", 100);
            String key = UUID.randomUUID().toString();
            var theirs = record(b, invoiceB, key, pay("100.00"));
            assertThat(theirs.status()).isEqualTo(200);

            var mine = record(a, invoiceA, key, pay("200.00"));

            assertThat(mine.status()).isEqualTo(200);
            assertThat(mine.data().get("creditInvoiceId").asLong()).describedAs("not B's payment").isEqualTo(invoiceA);
            assertThat(mine.data().get("id").asLong()).isNotEqualTo(theirs.data().get("id").asLong());
            assertThat(paid(invoiceA)).isEqualByComparingTo("200.00");
            assertThat(paid(invoiceB)).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("two suppliers using the same key string each record their own payment")
        void sameKeyStringTwoTenantsTwoPayments() throws Exception {
            var a = s.creditLine("200000");
            var b = s.creditLine("200000");
            long invoiceA = s.invoice(a, "65", 100);
            long invoiceB = s.invoice(b, "65", 100);
            String key = "shared-key-string";

            assertThat(record(a, invoiceA, key, pay("100.00")).status()).isEqualTo(200);
            assertThat(record(b, invoiceB, key, pay("300.00")).status()).isEqualTo(200);

            assertThat(payments(invoiceA)).hasSize(1);
            assertThat(payments(invoiceB)).hasSize(1);
            assertThat(paid(invoiceA)).isEqualByComparingTo("100.00");
            assertThat(paid(invoiceB)).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("the same supplier and key twice is one payment, and the replay is the same answer")
        void sameSupplierSameKeyOnePayment() throws Exception {
            var a = s.creditLine("200000");
            long invoice = s.invoice(a, "65", 100);
            String key = UUID.randomUUID().toString();

            var first = record(a, invoice, key, pay("100.00"));
            var second = record(a, invoice, key, pay("100.00"));

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.data()).isEqualTo(first.data());
            assertThat(payments(invoice)).hasSize(1);
            assertThat(paid(invoice)).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("a supplier squatting 'claim:<id>' or 'wallet-repayment:...' keys cannot break another tenant's confirm or repayment")
        void squattingInternalKeysBreaksNothing() throws Exception {
            var a = s.creditLine("200000");
            var b = s.creditLine("200000");
            long invoiceA = s.invoice(a, "65", 100);
            long invoiceB = s.invoice(b, "65", 100);
            long claimId = submitted(b, invoiceB, "2500.00");
            long nextRepayment = jdbc.queryForObject("select coalesce(max(id), 0) + 1 from credit_repayment", Long.class);
            // A squats B's claim key and the keys B's repayment is about to use.
            assertThat(record(a, invoiceA, "claim:" + claimId, pay("1.00")).status()).isEqualTo(200);
            for (long id = nextRepayment; id < nextRepayment + 3; id++) {
                assertThat(record(a, invoiceA, "wallet-repayment:" + id + ":" + invoiceB, pay("1.00")).status())
                        .isEqualTo(200);
            }
            s.topUp(b.buyer(), "20000.00");

            var confirmed = confirm(b, claimId, null);
            var repaid = s.repay(b.buyer().token(), b.agreementId(), UUID.randomUUID().toString(),
                    Map.of("amount", "1000.00"));

            assertThat(confirmed.status()).describedAs(confirmed.body().toString()).isEqualTo(200);
            assertThat(repaid.status()).describedAs(repaid.body().toString()).isEqualTo(201);
            assertThat(paid(invoiceB)).isEqualByComparingTo("3500.00");
        }

        @Test
        @DisplayName("a payment dated in the future is refused, and nothing is recorded")
        void futurePaidAtRefused() throws Exception {
            var a = s.creditLine("200000");
            long invoice = s.invoice(a, "65", 100);
            var body = pay("100.00");
            body.put("paidAt", Instant.now().plus(java.time.Duration.ofDays(2)).toString());

            var reply = record(a, invoice, UUID.randomUUID().toString(), body);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(reply.body().at("/error/message").asText()).contains("future");
            assertThat(payments(invoice)).isEmpty();
            assertThat(paid(invoice)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a payment dated before the invoice was issued is refused; today is accepted")
        void paidAtBeforeIssueRefused() throws Exception {
            var a = s.creditLine("200000");
            long invoice = s.invoice(a, "65", 100);
            var early = pay("100.00");
            early.put("paidAt", Instant.now().minus(java.time.Duration.ofDays(3)).toString());

            var refused = record(a, invoice, UUID.randomUUID().toString(), early);

            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(payments(invoice)).isEmpty();
            var ok = pay("100.00");
            ok.put("paidAt", Instant.now().minusSeconds(60).toString());
            assertThat(record(a, invoice, UUID.randomUUID().toString(), ok).status()).isEqualTo(200);
            assertThat(payments(invoice)).hasSize(1);
        }

        @Test
        @DisplayName("two first requests racing for one (outlet, store) pair: one wins, the loser is a clean 409 'already waiting'")
        void racingFirstRequestsLoserGetsACleanConflict() throws Exception {
            var buyer = s.newBuyer();
            for (int round = 0; round < 3; round++) {
                var seller = s.newSeller();
                mvc.perform(MockMvcRequestBuilders.put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                        .header("Authorization", "Bearer " + seller.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("creditEnabled", true,
                                "defaultCreditPeriodDays", 30, "defaultGracePeriodDays", 5))));
                // Both requests stop after reading the policy, so both then see "no agreement yet" and both insert.
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                org.mockito.Mockito.doAnswer(call -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.callRealMethod();
                }).when(directorySpy).creditPolicy(seller.storeId());
                var body = Map.of("supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                        "requestedLimit", "50000", "requestedDays", 30, "purpose", "PROCUREMENT");

                var one = pool.submit(() -> call("POST", buyer.token(), "/api/v1/credit/requests", null, body));
                var two = pool.submit(() -> call("POST", buyer.token(), "/api/v1/credit/requests", null, body));
                var replies = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));

                var statuses = replies.stream().map(Reply::status).sorted().toList();
                assertThat(statuses).describedAs("round %d: %s", round, replies.stream().map(r -> r.body().toString()).toList())
                        .hasSize(2).doesNotContain(500);
                assertThat(statuses.stream().filter(code -> code >= 200 && code < 300)).hasSize(1);
                var loser = replies.stream().filter(r -> r.status() >= 400).findFirst().orElseThrow();
                assertThat(loser.status()).describedAs(loser.body().toString()).isEqualTo(409);
                assertThat(loser.body().at("/error/message").asText())
                        .isEqualTo("A request to this supplier is already waiting for a response.");
                assertThat(jdbc.queryForObject("select count(*) from credit_agreement where outlet_id = ? "
                        + "and supplier_store_id = ?", Integer.class, buyer.outletId(), seller.storeId())).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from credit_request where outlet_id = ? "
                        + "and supplier_store_id = ?", Integer.class, buyer.outletId(), seller.storeId())).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("withdrawing twice: the retry gets 200 and the claim, with no second audit row")
        void withdrawRetryIsIdempotent() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");

            var first = withdraw(line.buyer().token(), claimId);
            var retry = withdraw(line.buyer().token(), claimId);

            assertThat(first.status()).isEqualTo(200);
            assertThat(retry.status()).describedAs(retry.body().toString()).isEqualTo(200);
            assertThat(retry.data().get("status").asText()).isEqualTo("WITHDRAWN");
            assertThat(retry.data().get("id").asLong()).isEqualTo(claimId);
            assertThat(auditRows("CREDIT_CLAIM_WITHDRAWN", claimId)).isEqualTo(1);
        }

        @Test
        @DisplayName("rejecting twice with the same reason: the retry gets 200, one event; another reason or state is still 409")
        void rejectRetryIsIdempotent() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            long claimId = submitted(line, invoice, "2500.00");

            var first = reject(line.seller().token(), claimId, "Not in our statement.");
            var retry = reject(line.seller().token(), claimId, "  Not in our statement.  ");
            var other = reject(line.seller().token(), claimId, "A different reason entirely.");

            assertThat(first.status()).isEqualTo(200);
            assertThat(retry.status()).describedAs(retry.body().toString()).isEqualTo(200);
            assertThat(retry.data().get("status").asText()).isEqualTo("REJECTED");
            assertThat(auditRows("CREDIT_CLAIM_REJECTED", claimId)).isEqualTo(1);
            assertThat(events("CreditClaimRejected", invoice)).isEqualTo(1);
            assertThat(other.status()).isEqualTo(409);
            assertThat(other.code()).isEqualTo("CREDIT_CLAIM_STATE");
            // A claim in a different final state is never answered as the one asked for.
            long second = submitted(line, invoice, "100.00");
            assertThat(withdraw(line.buyer().token(), second).status()).isEqualTo(200);
            assertThat(reject(line.seller().token(), second, "Not in our statement.").code())
                    .isEqualTo("CREDIT_CLAIM_STATE");
        }
    }
}
