package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditReadService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The buyer's order carries the credit dates its bill needs: "On credit, due {date}" while the invoice is open and
 * "Paid on {date}" once it is settled. Additive nullable fields on the supplier-order response; the app does no date
 * arithmetic, it shows what the server sends (D-154).
 */
@AutoConfigureMockMvc
class CreditOrderDatesIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @MockBean(name = "creditClock") private Clock creditClock;
    @SpyBean private CreditReadService creditRead;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
        Instant at = ZonedDateTime.parse("2026-03-10T12:00:00+05:30[Asia/Kolkata]").toInstant();
        when(creditClock.getZone()).thenReturn(IST);
        when(creditClock.instant()).thenReturn(at);
    }

    private Reply get(String token, String path) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private long orderOf(long invoiceId) {
        return jdbc.queryForObject("select supplier_order_id from credit_invoice where id = ?", Long.class, invoiceId);
    }

    private JsonNode order(Line line, long invoiceId) throws Exception {
        var reply = get(line.buyer().token(), "/api/v1/supplier-orders/" + orderOf(invoiceId));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private void dueIn(long invoiceId, int days) {
        LocalDate due = TODAY.plusDays(days);
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?", due, due.plusDays(5), invoiceId);
    }

    @Test
    @DisplayName("an unsettled credit order carries its due date and state, and no settled date")
    void unsettled() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        dueIn(invoice, 12);

        var d = order(line, invoice);
        assertThat(d.get("paymentMethod").asText()).isEqualTo("CREDIT");
        assertThat(d.get("creditDueDate").asText()).isEqualTo("2026-03-22");
        assertThat(d.get("creditDueState").asText()).isEqualTo("DUE_LATER");
        assertThat(d.get("creditSettledAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("the supplier's order-confirmed event names the restaurant and says on credit with the due date (flow review 6)")
    void confirmationEventIsWordedForCredit() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        String dueText = jdbc.queryForObject("select due_date from credit_invoice where id = ?", java.sql.Date.class, invoice)
                .toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));

        var payload = json.readTree(jdbc.queryForObject("""
                select payload from outbox_event where aggregate_type = 'SUPPLIER_ORDER' and aggregate_id = ?
                   and event_type = 'SupplierOrderConfirmed'""", String.class, orderOf(invoice)));

        assertThat(payload.get("notificationVariant").asText()).isEqualTo("CREDIT_DUE");
        assertThat(payload.get("dueDate").asText()).isEqualTo(dueText);
        assertThat(payload.get("restaurantName").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the state is the credit module's: past the grace period it is OVERDUE, due today it is DUE_TODAY")
    void dueState() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        dueIn(invoice, 0);
        assertThat(order(line, invoice).get("creditDueState").asText()).isEqualTo("DUE_TODAY");
        dueIn(invoice, -10);
        assertThat(order(line, invoice).get("creditDueState").asText()).isEqualTo("OVERDUE");
    }

    @Test
    @DisplayName("a partial repayment leaves it due, with no settled date")
    void partial() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        dueIn(invoice, 12);
        assertThat(s.recordPayment(line.seller(), invoice, "2500.00").status()).isEqualTo(200);

        var d = order(line, invoice);
        assertThat(d.get("creditDueDate").asText()).isEqualTo("2026-03-22");
        assertThat(d.get("creditSettledAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("full repayment: the settled date is when the invoice settled, and nothing is due any more")
    void settled() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        dueIn(invoice, 12);
        assertThat(s.recordPayment(line.seller(), invoice, "2500.00").status()).isEqualTo(200);
        assertThat(s.recordPayment(line.seller(), invoice, "4000.00").status()).isEqualTo(200);

        var d = order(line, invoice);
        assertThat(jdbc.queryForObject("select settled_at is not null from credit_invoice where id = ?",
                Boolean.class, invoice)).isTrue();
        // Stamped by the settling payment just now (the real instant, UTC on the wire).
        assertThat(Instant.parse(d.get("creditSettledAt").asText()))
                .isBetween(Instant.now().minusSeconds(300), Instant.now().plusSeconds(5));
        assertThat(d.get("creditDueDate").isNull()).isTrue();
        assertThat(d.get("creditDueState").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a written-off invoice is settled, as CreditInvoiceStatus says; credited-but-open is not")
    void writtenOffCountsAsSettled() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        // Credited in part: still owed, so still due.
        jdbc.update("update credit_invoice set credited_amount = 100 where id = ?", invoice);
        assertThat(order(line, invoice).get("creditSettledAt").isNull()).isTrue();
        jdbc.update("update credit_invoice set status = 'WRITTEN_OFF', settled_at = '2026-03-09 10:00:00' where id = ?",
                invoice);
        var d = order(line, invoice);
        assertThat(Instant.parse(d.get("creditSettledAt").asText())).isEqualTo(Instant.parse("2026-03-09T10:00:00Z"));
        assertThat(d.get("creditDueDate").isNull()).isTrue();
        assertThat(d.get("creditDueState").asText()).isEqualTo("WRITTEN_OFF");
    }

    @Test
    @DisplayName("a non-credit order has all three fields null")
    void nonCredit() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        jdbc.update("update supplier_order set payment_method = 'WALLET' where id = ?", orderOf(invoice));

        var d = order(line, invoice);
        assertThat(d.get("paymentMethod").asText()).isEqualTo("WALLET");
        assertThat(d.has("creditDueDate")).isTrue();
        assertThat(d.get("creditDueDate").isNull()).isTrue();
        assertThat(d.get("creditSettledAt").isNull()).isTrue();
        assertThat(d.get("creditDueState").isNull()).isTrue();
    }

    @Test
    @DisplayName("the outlet's list gets each order's own dates from one credit read, not one per order")
    void listIsBatched() throws Exception {
        var line = s.creditLine("500000");
        long first = s.invoice(line, "65", 10);
        long second = s.invoice(line, "40", 10);
        long third = s.invoice(line, "30", 10);
        dueIn(first, 5);
        dueIn(second, 20);
        jdbc.update("update supplier_order set payment_method = 'WALLET' where id = ?", orderOf(third));
        assertThat(s.recordPayment(line.seller(), first, "100.00").status()).isEqualTo(200);

        clearInvocations(creditRead);
        var reply = get(line.buyer().token(), "/api/v1/outlets/" + line.buyer().outletId() + "/supplier-orders");
        assertThat(reply.status()).isEqualTo(200);
        verify(creditRead, times(1)).orderCredit(any());

        JsonNode byFirst = null, bySecond = null, byThird = null;
        for (JsonNode o : reply.data()) {
            long id = o.get("id").asLong();
            if (id == orderOf(first)) byFirst = o;
            if (id == orderOf(second)) bySecond = o;
            if (id == orderOf(third)) byThird = o;
        }
        assertThat(byFirst).isNotNull();
        assertThat(byFirst.get("creditDueDate").asText()).isEqualTo("2026-03-15");
        assertThat(byFirst.get("creditSettledAt").isNull()).isTrue();
        assertThat(bySecond.get("creditDueDate").asText()).isEqualTo("2026-03-30");
        assertThat(byThird.get("creditDueDate").isNull()).isTrue();
    }

    @Test
    @DisplayName("who may read the order is unchanged: another restaurant still gets a 404, the supplier still reads it")
    void permissionsUnchanged() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        var path = "/api/v1/supplier-orders/" + orderOf(invoice);
        var stranger = s.newBuyer();
        assertThat(get(stranger.token(), path).status()).isEqualTo(404);
        var seller = get(line.seller().token(), path);
        assertThat(seller.status()).isEqualTo(200);
        assertThat(seller.data().get("creditDueDate").isNull()).isFalse();
    }
}
