package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * What the restaurant's Credit screens read (D-154): the Home attention dot, due states, the overview's
 * next-due figures, an invoice with its payments, and the statement. The server sends states and numbers; the
 * app does no date or money arithmetic, so these tests pin the days to India time.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditRestaurantReadsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @MockBean(name = "creditClock") private Clock creditClock;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
        freezeAt("2026-03-10T12:00:00");
    }

    private void freezeAt(String istDateTime) {
        Instant at = ZonedDateTime.parse(istDateTime + "+05:30[Asia/Kolkata]").toInstant();
        when(creditClock.getZone()).thenReturn(IST);
        when(creditClock.instant()).thenReturn(at);
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    private Reply get(String token, String path) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    /** An invoice of {@code price x 100} on the line, dated relative to the frozen today and with the given status. */
    private long invoiceDue(Line line, String price, int dueInDays, int graceDays, String status) throws Exception {
        long id = s.invoice(line, price, 100);
        LocalDate due = TODAY.plusDays(dueInDays);
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ?, status = ? where id = ?",
                due, due.plusDays(graceDays), status, id);
        if (status.equals("PAID")) {
            jdbc.update("update credit_invoice set paid_amount = amount where id = ?", id);
        }
        return id;
    }

    private JsonNode attention(CreditWalletSupport.Buyer buyer) throws Exception {
        var reply = get(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/credit/attention");
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    // ── 1. attention ─────────────────────────────────────────────────────

    @Nested
    class Attention {

        @Test
        @DisplayName("nothing owed: neither flag, and no amounts in the answer")
        void none() throws Exception {
            var line = s.creditLine("200000");
            var d = attention(line.buyer());
            assertThat(d.get("overdue").asBoolean()).isFalse();
            assertThat(d.get("dueSoon").asBoolean()).isFalse();
            assertThat(d.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("an OVERDUE invoice sets overdue, and is not also reported as due soon")
        void overdueOnly() throws Exception {
            var line = s.creditLine("200000");
            invoiceDue(line, "65", -10, 5, "OVERDUE");
            var d = attention(line.buyer());
            assertThat(d.get("overdue").asBoolean()).isTrue();
            assertThat(d.get("dueSoon").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("an open invoice due within three days sets dueSoon; four days out does not")
        void dueSoon() throws Exception {
            var line = s.creditLine("200000");
            long later = invoiceDue(line, "65", 4, 5, "ISSUED");
            assertThat(attention(line.buyer()).get("dueSoon").asBoolean()).isFalse();
            invoiceDue(line, "40", 3, 5, "PARTIALLY_PAID");
            var d = attention(line.buyer());
            assertThat(d.get("dueSoon").asBoolean()).isTrue();
            assertThat(d.get("overdue").asBoolean()).isFalse();
            assertThat(later).isPositive();
        }

        @Test
        @DisplayName("past due but inside the grace period counts as due soon, not overdue")
        void inGrace() throws Exception {
            var line = s.creditLine("200000");
            invoiceDue(line, "65", -1, 5, "ISSUED");
            var d = attention(line.buyer());
            assertThat(d.get("dueSoon").asBoolean()).isTrue();
            assertThat(d.get("overdue").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("one overdue invoice and another due soon: both flags")
        void both() throws Exception {
            var line = s.creditLine("200000");
            invoiceDue(line, "65", -10, 5, "OVERDUE");
            invoiceDue(line, "40", 2, 5, "ISSUED");
            var d = attention(line.buyer());
            assertThat(d.get("overdue").asBoolean()).isTrue();
            assertThat(d.get("dueSoon").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("paid and written-off invoices are ignored, however old or near")
        void settledIgnored() throws Exception {
            var line = s.creditLine("200000");
            invoiceDue(line, "65", -10, 5, "PAID");
            invoiceDue(line, "40", 1, 5, "WRITTEN_OFF");
            var d = attention(line.buyer());
            assertThat(d.get("overdue").asBoolean()).isFalse();
            assertThat(d.get("dueSoon").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("another outlet's invoices never show, and its outlet id is a 404 for everyone else")
        void isolation() throws Exception {
            var mine = s.creditLine("200000");
            var theirs = s.creditLine("200000");
            invoiceDue(theirs, "65", -10, 5, "OVERDUE");
            invoiceDue(theirs, "40", 1, 5, "ISSUED");

            var d = attention(mine.buyer());
            assertThat(d.get("overdue").asBoolean()).isFalse();
            assertThat(d.get("dueSoon").asBoolean()).isFalse();

            var path = "/api/v1/outlets/" + theirs.buyer().outletId() + "/credit/attention";
            assertThat(get(mine.buyer().token(), path).status()).isEqualTo(404);
            // The supplier on the line has no restaurant-side permission on the outlet either.
            assertThat(get(theirs.seller().token(), path).status()).isEqualTo(404);
            assertThat(attention(theirs.buyer()).get("overdue").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("today is the India date: at 01:00 IST the window ends on the 13th, though UTC is still the 9th")
        void istBoundary() throws Exception {
            freezeAt("2026-03-10T01:00:00");
            var line = s.creditLine("200000");
            invoiceDue(line, "65", 4, 5, "ISSUED");
            assertThat(attention(line.buyer()).get("dueSoon").asBoolean()).isFalse();
            invoiceDue(line, "40", 3, 5, "ISSUED");
            assertThat(attention(line.buyer()).get("dueSoon").asBoolean())
                    .describedAs("due on the 13th is three days after the 10th in India").isTrue();
        }
    }

    @Nested
    @DisplayName("attention follows the one overdue rule (B18)")
    class AttentionFollowsDueState {

        @Test
        @DisplayName("past overdue_after but not yet marked by the sweep: overdue is already true")
        void unmarkedPastGraceIsOverdue() throws Exception {
            var line = s.creditLine("200000");
            invoiceDue(line, "65", -10, 5, "ISSUED");
            var d = attention(line.buyer());
            assertThat(d.get("overdue").asBoolean()).isTrue();
            assertThat(d.get("dueSoon").asBoolean()).describedAs("an overdue one is not also due soon").isFalse();
        }

        @Test
        @DisplayName("on the boundary day (overdue_after == today) it is still in grace: overdue false")
        void boundaryDayIsNotOverdue() throws Exception {
            var line = s.creditLine("200000");
            invoiceDue(line, "65", -5, 5, "ISSUED");
            assertThat(attention(line.buyer()).get("overdue").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("marked OVERDUE: true; paid: false")
        void markedAndPaid() throws Exception {
            var marked = s.creditLine("200000");
            invoiceDue(marked, "65", -1, 5, "OVERDUE");
            assertThat(attention(marked.buyer()).get("overdue").asBoolean()).isTrue();
            var paid = s.creditLine("200000");
            invoiceDue(paid, "65", -30, 5, "PAID");
            assertThat(attention(paid.buyer()).get("overdue").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("the SQL predicate agrees with CreditDueState.of for every status and a window of dates")
        void predicateMatchesDueState() throws Exception {
            var line = s.creditLine("200000");
            long id = invoiceDue(line, "65", 0, 0, "ISSUED");
            for (String status : List.of("ISSUED", "PARTIALLY_PAID", "OVERDUE", "PAID", "WRITTEN_OFF")) {
                for (int dueIn = -12; dueIn <= 3; dueIn++) {
                    for (int grace : new int[]{0, 2, 5}) {
                        LocalDate due = TODAY.plusDays(dueIn);
                        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ?, status = ? where id = ?",
                                due, due.plusDays(grace), status, id);
                        boolean rule = com.costonomy.mp.credit.domain.CreditDueState.of(
                                com.costonomy.mp.credit.domain.CreditInvoiceStatus.valueOf(status), due,
                                due.plusDays(grace), TODAY) == com.costonomy.mp.credit.domain.CreditDueState.OVERDUE;
                        assertThat(attention(line.buyer()).get("overdue").asBoolean())
                                .describedAs("%s due in %d grace %d", status, dueIn, grace).isEqualTo(rule);
                    }
                }
            }
        }
    }

    @Nested
    class DueStatesAndSummary {

        // ── 2. due state on invoices ─────────────────────────────────────────

        @Test
        @DisplayName("every invoice carries a server-computed dueState and daysToDue")
        void invoicesCarryDueState() throws Exception {
            var line = s.creditLine("500000");
            long paid = invoiceDue(line, "10", -3, 0, "PAID");
            long off = invoiceDue(line, "10", -3, 0, "WRITTEN_OFF");
            long overdue = invoiceDue(line, "10", -10, 5, "OVERDUE");
            long grace = invoiceDue(line, "10", -1, 5, "ISSUED");
            long today = invoiceDue(line, "10", 0, 5, "ISSUED");
            long soon = invoiceDue(line, "10", 3, 5, "ISSUED");
            long later = invoiceDue(line, "10", 4, 5, "PARTIALLY_PAID");

            var reply = get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/invoices");
            assertThat(reply.status()).isEqualTo(200);
            var byId = new java.util.HashMap<Long, JsonNode>();
            reply.data().forEach(n -> byId.put(n.get("id").asLong(), n));

            expect(byId.get(paid), "PAID", null);
            expect(byId.get(off), "WRITTEN_OFF", null);
            expect(byId.get(overdue), "OVERDUE", -10);
            expect(byId.get(grace), "IN_GRACE", -1);
            expect(byId.get(today), "DUE_TODAY", 0);
            expect(byId.get(soon), "DUE_SOON", 3);
            expect(byId.get(later), "DUE_LATER", 4);
        }

        private static void expect(JsonNode invoice, String state, Integer days) {
            assertThat(invoice.get("dueState").asText()).isEqualTo(state);
            if (days == null) {
                assertThat(invoice.get("daysToDue").isNull()).isTrue();
            } else {
                assertThat(invoice.get("daysToDue").asInt()).isEqualTo(days);
            }
        }

        // ── 3. summary ───────────────────────────────────────────────────────

        @Test
        @DisplayName("the summary adds next due, open invoices and the wallet-repay flag; a line that owes nothing is still listed")
        void summaryNextDue() throws Exception {
            var first = s.creditLine("500000");
            var second = s.creditLine(first.buyer(), "500000", Map.of());
            invoiceDue(first, "65", 10, 5, "ISSUED");          // 6500, later
            invoiceDue(first, "40", 2, 5, "ISSUED");           // 4000, the earliest
            invoiceDue(first, "10", 2, 5, "PARTIALLY_PAID");   // 1000, same day
            invoiceDue(first, "20", -4, 0, "PAID");            // settled, not counted

            var reply = get(first.buyer().token(), "/api/v1/outlets/" + first.buyer().outletId() + "/credit/summary");
            assertThat(reply.status()).isEqualTo(200);
            var d = reply.data();
            assertThat(d.get("walletRepayEnabled").asBoolean()).isTrue();
            assertThat(d.get("agreements")).hasSize(2);

            JsonNode a = null;
            JsonNode b = null;
            for (JsonNode n : d.get("agreements")) {
                if (n.get("id").asLong() == first.agreementId()) a = n;
                if (n.get("id").asLong() == second.agreementId()) b = n;
            }
            assertThat(a.get("openInvoices").asInt()).isEqualTo(3);
            assertThat(a.get("nextDueDate").asText()).isEqualTo(TODAY.plusDays(2).toString());
            assertThat(a.get("nextDueAmount").decimalValue()).isEqualByComparingTo("5000.00");
            assertThat(a.get("due").decimalValue()).isEqualByComparingTo("11500.00");

            assertThat(b.get("openInvoices").asInt()).isZero();
            assertThat(b.get("nextDueDate").isNull()).isTrue();
            assertThat(b.get("nextDueAmount").isNull()).isTrue();
        }


    }

    // ── 4. invoice detail ────────────────────────────────────────────────

    @Nested
    class InvoiceDetail {

        @Test
        @DisplayName("an invoice paid partly from the wallet and partly by the supplier shows both payments, newest first")
        void withPayments() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            s.topUp(line.buyer(), "5000.00");
            var wallet = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                    Map.of("amount", "1000.00", "invoiceIds", List.of(invoice)));
            assertThat(wallet.status()).isEqualTo(201);
            assertThat(s.recordPayment(line.seller(), invoice, "500.00").status()).isEqualTo(200);

            var reply = get(line.buyer().token(), "/api/v1/credit/invoices/" + invoice);
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            var d = reply.data();
            assertThat(d.get("id").asLong()).isEqualTo(invoice);
            assertThat(d.get("status").asText()).isEqualTo("PARTIALLY_PAID");
            assertThat(d.get("outstanding").decimalValue()).isEqualByComparingTo("5000.00");
            assertThat(d.get("dueState").asText()).isEqualTo("DUE_LATER");
            assertThat(d.get("daysToDue").asInt()).isEqualTo(30);
            assertThat(d.get("agreementId").asLong()).isEqualTo(line.agreementId());
            assertThat(d.get("supplierName").asText()).isEqualTo("ABC Foods");
            assertThat(d.get("storeName").asText()).isEqualTo("ABC store");
            String orderNumber = jdbc.queryForObject("select o.order_number from supplier_order o "
                    + "join credit_invoice i on i.supplier_order_id = o.id where i.id = ?", String.class, invoice);
            assertThat(d.get("orderNumber").asText()).isEqualTo(orderNumber).isNotBlank();

            var payments = d.get("payments");
            assertThat(payments).hasSize(2);
            var recorded = payments.get(0);
            assertThat(recorded.get("source").asText()).isEqualTo("SUPPLIER_RECORDED");
            assertThat(recorded.get("amount").decimalValue()).isEqualByComparingTo("500.00");
            assertThat(recorded.get("method").asText()).isEqualTo("BANK_TRANSFER");
            assertThat(recorded.get("reference").asText()).isEqualTo("UTR12345678");
            assertThat(recorded.get("walletEntryId").isNull()).isTrue();
            assertThat(recorded.get("paidAt").asText()).isNotBlank();

            var fromWallet = payments.get(1);
            assertThat(fromWallet.get("source").asText()).isEqualTo("WALLET");
            assertThat(fromWallet.get("amount").decimalValue()).isEqualByComparingTo("1000.00");
            assertThat(fromWallet.get("walletEntryId").asLong()).isEqualTo(wallet.data().get("walletEntryId").asLong());
            assertThat(fromWallet.get("id").asLong()).isPositive();

            // The supplier of the line reads the same invoice.
            assertThat(get(line.seller().token(), "/api/v1/credit/invoices/" + invoice).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("an invoice nobody paid has an empty payments list")
        void noPayments() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            var d = get(line.buyer().token(), "/api/v1/credit/invoices/" + invoice).data();
            assertThat(d.get("payments")).isEmpty();
        }

        @Test
        @DisplayName("anyone else, on either side, and an unknown id, get the same 404")
        void isolation() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            var other = s.creditLine("200000");
            var path = "/api/v1/credit/invoices/" + invoice;

            assertThat(get(other.buyer().token(), path).status()).isEqualTo(404);
            assertThat(get(other.seller().token(), path).status()).isEqualTo(404);
            assertThat(get(line.buyer().token(), "/api/v1/credit/invoices/999999999").status()).isEqualTo(404);
            assertThat(get(line.buyer().token(), path).status()).isEqualTo(200);
        }
    }

    // ── 5. statement ─────────────────────────────────────────────────────

    @Nested
    class Statement {

        private record Month(Line line, long invoice, long second, List<Long> ledgerIds) {
        }

        /** Sets a ledger row's time to an India local time, stored as UTC like every timestamp. */
        private void stamp(long ledgerId, String istDateTime) {
            Instant at = ZonedDateTime.parse(istDateTime + "+05:30[Asia/Kolkata]").toInstant();
            jdbc.update("update credit_transaction set created_at = ? where id = ?",
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC)),
                    ledgerId);
        }

        /**
         * 6500 ordered in February; 1000 repaid from the wallet on 5 March; 500 recorded by the supplier at 23:30 IST
         * on 31 March; a second order of 4000 at 00:00 IST on 1 April.
         */
        private Month month() throws Exception {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "65", 100);
            s.topUp(line.buyer(), "5000.00");
            assertThat(s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                    Map.of("amount", "1000.00", "invoiceIds", List.of(invoice))).status()).isEqualTo(201);
            assertThat(s.recordPayment(line.seller(), invoice, "500.00").status()).isEqualTo(200);
            long second = s.invoice(line, "40", 100);

            var ids = jdbc.queryForList("select id from credit_transaction where credit_agreement_id = ? order by id",
                    Long.class, line.agreementId());
            // RESERVE, UTILIZE, REPAYMENT (wallet), REPAYMENT (supplier), RESERVE, UTILIZE
            assertThat(ids).hasSize(6);
            stamp(ids.get(0), "2026-02-20T10:00:00");
            stamp(ids.get(1), "2026-02-20T10:00:01");
            stamp(ids.get(2), "2026-03-05T09:00:00");
            stamp(ids.get(3), "2026-03-31T23:30:00");
            stamp(ids.get(4), "2026-04-01T00:00:00");
            stamp(ids.get(5), "2026-04-01T00:00:01");
            return new Month(line, invoice, second, ids);
        }

        private JsonNode statement(Line line, String query) throws Exception {
            var reply = get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId()
                    + "/statement" + query);
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
            return reply.data();
        }

        private void assertAddsUp(JsonNode d) {
            BigDecimal sum = BigDecimal.ZERO;
            for (JsonNode l : d.get("lines")) {
                sum = sum.add(l.get("amount").decimalValue());
            }
            assertThat(d.get("openingOwed").decimalValue().add(sum))
                    .isEqualByComparingTo(d.get("closingOwed").decimalValue());
        }

        @Test
        @DisplayName("March: the wallet repayment and the supplier-recorded one, newest first; 23:30 IST on the 31st is in")
        void march() throws Exception {
            var m = month();
            var d = statement(m.line(), "?from=2026-03-01&to=2026-03-31");

            assertThat(d.get("from").asText()).isEqualTo("2026-03-01");
            assertThat(d.get("to").asText()).isEqualTo("2026-03-31");
            assertThat(d.get("openingOwed").decimalValue()).isEqualByComparingTo("6500.00");
            assertThat(d.get("closingOwed").decimalValue()).isEqualByComparingTo("5000.00");
            assertAddsUp(d);

            var lines = d.get("lines");
            assertThat(lines).hasSize(2);
            var supplier = lines.get(0);
            assertThat(supplier.get("type").asText()).isEqualTo("REPAYMENT");
            assertThat(supplier.get("label").asText()).isEqualTo("Repayment");
            assertThat(supplier.get("amount").decimalValue()).isEqualByComparingTo("-500.00");
            assertThat(supplier.get("owedAfter").decimalValue()).isEqualByComparingTo("5000.00");
            assertThat(supplier.get("source").asText()).isEqualTo("SUPPLIER_RECORDED");
            assertThat(supplier.get("method").asText()).isEqualTo("BANK_TRANSFER");
            assertThat(supplier.get("reference").asText()).isEqualTo("UTR12345678");
            assertThat(supplier.get("walletEntryId").isNull()).isTrue();
            assertThat(supplier.get("creditInvoiceId").asLong()).isEqualTo(m.invoice());
            assertThat(supplier.get("invoiceNumber").asText()).isNotBlank();

            var wallet = lines.get(1);
            assertThat(wallet.get("amount").decimalValue()).isEqualByComparingTo("-1000.00");
            assertThat(wallet.get("owedAfter").decimalValue()).isEqualByComparingTo("5500.00");
            assertThat(wallet.get("source").asText()).isEqualTo("WALLET");
            assertThat(wallet.get("walletEntryId").asLong()).isPositive();
            assertThat(wallet.get("at").asText()).isNotBlank();
        }

        @Test
        @DisplayName("the next India day is out: 00:00 IST on 1 April belongs to April, and RESERVE rows never show")
        void dayBoundary() throws Exception {
            var m = month();
            var april = statement(m.line(), "?from=2026-04-01&to=2026-04-01");
            assertThat(april.get("openingOwed").decimalValue()).isEqualByComparingTo("5000.00");
            assertThat(april.get("closingOwed").decimalValue()).isEqualByComparingTo("9000.00");
            assertThat(april.get("lines")).hasSize(1);
            var order = april.get("lines").get(0);
            assertThat(order.get("type").asText()).isEqualTo("UTILIZE");
            assertThat(order.get("label").asText()).isEqualTo("Order on credit");
            assertThat(order.get("amount").decimalValue()).isEqualByComparingTo("4000.00");
            assertThat(order.get("owedAfter").decimalValue()).isEqualByComparingTo("9000.00");
            assertThat(order.get("supplierOrderId").asLong()).isPositive();
            assertThat(order.get("orderNumber").asText()).isNotBlank();
            assertThat(order.get("invoiceNumber").asText()).isNotBlank();
            assertAddsUp(april);

            var all = statement(m.line(), "?from=2026-02-01&to=2026-04-30");
            assertThat(all.get("openingOwed").decimalValue()).isEqualByComparingTo("0");
            assertThat(all.get("closingOwed").decimalValue()).isEqualByComparingTo("9000.00");
            assertThat(all.get("lines")).hasSize(4);
            for (JsonNode l : all.get("lines")) {
                assertThat(l.get("type").asText()).isNotEqualTo("RESERVE");
            }
            assertAddsUp(all);
        }

        @Test
        @DisplayName("with no dates it covers the last 90 days up to today in India")
        void defaults() throws Exception {
            var m = month();
            var d = statement(m.line(), "");
            assertThat(d.get("to").asText()).isEqualTo("2026-03-10");
            assertThat(d.get("from").asText()).isEqualTo("2025-12-11");
            assertAddsUp(d);
        }

        @Test
        @DisplayName("to before from, a range over 366 days, and a bad date are validation errors; 366 days is fine")
        void validation() throws Exception {
            var line = s.creditLine("200000");
            String base = "/api/v1/credit/agreements/" + line.agreementId() + "/statement";
            var token = line.buyer().token();
            var backwards = get(token, base + "?from=2026-03-10&to=2026-03-01");
            assertThat(backwards.status()).isEqualTo(400);
            assertThat(backwards.code()).isEqualTo("VALIDATION_ERROR");
            var tooLong = get(token, base + "?from=2024-01-01&to=2025-01-01");
            assertThat(tooLong.status()).isEqualTo(400);
            assertThat(tooLong.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(get(token, base + "?from=yesterday&to=2026-03-01").status()).isEqualTo(400);
            assertThat(get(token, base + "?from=2024-01-01&to=2024-12-31").status()).isEqualTo(200);
        }

        @Test
        @DisplayName("the supplier of the line reads it; anyone else gets a 404")
        void isolation() throws Exception {
            var line = s.creditLine("200000");
            s.invoice(line, "65", 100);
            var other = s.creditLine("200000");
            String path = "/api/v1/credit/agreements/" + line.agreementId() + "/statement";
            assertThat(get(line.seller().token(), path).status()).isEqualTo(200);
            assertThat(get(other.buyer().token(), path).status()).isEqualTo(404);
            assertThat(get(other.seller().token(), path).status()).isEqualTo(404);
        }
    }
}
