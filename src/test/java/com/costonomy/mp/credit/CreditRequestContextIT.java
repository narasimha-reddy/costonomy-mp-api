package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a supplier sees about a restaurant asking it for credit (B12, RQ12, RQ13, D-139): this store's own history with
 * the outlet and nothing about any other supplier (privacy, decision 15).
 */
@AutoConfigureMockMvc
class CreditRequestContextIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private NotificationRelayAccess relay;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private CreditLifecycleSupport l;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        l = new CreditLifecycleSupport(e, jdbc, relay);
    }

    private Reply context(String token, long storeId, long agreementId) throws Exception {
        return e.call("GET", token, "/api/v1/supplier-stores/" + storeId + "/credit/requests/" + agreementId
                + "/context", null, null);
    }

    private Reply context(Line line) throws Exception {
        return context(line.seller().token(), line.seller().storeId(), line.agreementId());
    }

    private void orderDaysAgo(long invoice, int days) {
        jdbc.update("update supplier_order set created_at = date_sub(now(6), interval ? day) "
                + "where id = (select supplier_order_id from credit_invoice where id = ?)", days, invoice);
    }

    private Reply rerequest(Line line) throws Exception {
        return e.call("POST", line.buyer().token(), "/api/v1/credit/requests", null,
                Map.of("supplierStoreId", line.seller().storeId(), "outletId", line.buyer().outletId(),
                        "requestedLimit", "80000", "requestedDays", 15));
    }

    @Test
    @DisplayName("RQ12: orders in the last 90 days with this store, last order date, average, past line and overdue history")
    void showsThisStoresHistory() throws Exception {
        var buyer = s.newBuyer();
        var a = s.creditLine(buyer, "500000", Map.of());

        long old = s.invoice(a, "100", 10);          // 1,000, 100 days ago, later overdue and paid
        long second = s.invoice(a, "300", 10);       // 3,000
        long third = s.invoice(a, "200", 10);        // 2,000
        long cancelled = s.invoice(a, "50", 10);     // 500, cancelled
        orderDaysAgo(old, 100);
        jdbc.update("update supplier_order set status = 'CANCELLED' where id = "
                + "(select supplier_order_id from credit_invoice where id = ?)", cancelled);
        s.age(old, 10);
        creditJobs.sweepOverdue();
        assertThat(jdbc.queryForObject("select status from credit_invoice where id = ?", String.class, old))
                .isEqualTo("OVERDUE");
        for (long invoice : new long[]{old, second, third, cancelled}) {
            long outstanding = jdbc.queryForObject("select amount - paid_amount from credit_invoice where id = ?",
                    Long.class, invoice);
            assertThat(s.recordPayment(a.seller(), invoice, outstanding + ".00").status()).isEqualTo(200);
        }
        assertThat(e.call("POST", a.seller().token(), "/api/v1/credit/agreements/" + a.agreementId() + "/close", null,
                Map.of("reason", "Paid up, pausing")).status()).isEqualTo(200);

        // Another supplier of the same outlet: its numbers must never show up in A's context.
        var b = s.creditLine(buyer, "900000", Map.of());
        long[] bInvoices = new long[5];
        for (int i = 0; i < 5; i++) {
            bInvoices[i] = s.invoice(b, "1000", 10);
        }
        for (int i = 0; i < 3; i++) {
            s.age(bInvoices[i], 10);
        }
        creditJobs.sweepOverdue();

        var again = rerequest(a);
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);

        var reply = context(a);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var d = reply.data();
        assertThat(d.at("/agreementId").asLong()).isEqualTo(a.agreementId());
        assertThat(d.at("/status").asText()).isEqualTo("REQUESTED");
        assertThat(d.at("/outletId").asLong()).isEqualTo(buyer.outletId());
        assertThat(d.at("/restaurantName").asText()).isEqualTo("Paradise");
        assertThat(d.at("/ordersCount90d").asInt()).describedAs("the old order and the cancelled one are not counted")
                .isEqualTo(2);
        assertThat(d.at("/ordersValue90d").decimalValue()).isEqualByComparingTo("5000");
        assertThat(d.at("/averageOrderValue").decimalValue()).isEqualByComparingTo("2500");
        assertThat(d.at("/cancelledOrders90d").asInt()).isEqualTo(1);
        assertThat(d.at("/lastOrderDate").asText()).isEqualTo(CreditEdgeSupport.today().toString());
        assertThat(d.at("/firstOrderDate").asText()).isEqualTo(CreditEdgeSupport.today().minusDays(100).toString());
        assertThat(d.at("/previousOverdueCount").asInt()).describedAs("only this store's late invoices").isEqualTo(1);
        assertThat(d.at("/pastLineStatus").asText()).isEqualTo("CLOSED");
        assertThat(d.at("/pastLineEndedAt").asText()).isNotBlank();
        var history = d.at("/history");
        assertThat(history.isArray()).isTrue();
        assertThat(history.get(0).at("/event").asText()).describedAs("newest first").isEqualTo("REQUESTED");
        boolean sawClosed = false;
        for (var item : history) {
            if (item.at("/event").asText().equals("CLOSED")) {
                sawClosed = true;
                assertThat(item.at("/note").asText()).isEqualTo("Paid up, pausing");
                assertThat(item.at("/at").asText()).isNotBlank();
            }
        }
        assertThat(sawClosed).isTrue();

        // Privacy: nothing of supplier B (5 orders worth 50,000, 3 late) appears anywhere in the answer.
        String text = reply.body().toString();
        assertThat(text).doesNotContain("\"" + b.seller().storeId() + "\"");
        assertThat(text).doesNotContain("\"ordersValue90d\":50000").doesNotContain("\"ordersCount90d\":5");
        assertThat(text).doesNotContain("agreementId\":" + b.agreementId());
    }

    @Test
    @DisplayName("RQ13: B's context for the same outlet shows B's numbers only, A's none")
    void eachStoreSeesOnlyItsOwn() throws Exception {
        var buyer = s.newBuyer();
        var a = s.creditLine(buyer, "500000", Map.of());
        var b = s.creditLine(buyer, "900000", Map.of());
        s.invoice(a, "100", 10);
        s.invoice(b, "1000", 10);
        s.invoice(b, "1000", 10);
        s.invoice(b, "1000", 10);

        var forA = context(a).data();
        var forB = context(b).data();

        assertThat(forA.at("/ordersCount90d").asInt()).isEqualTo(1);
        assertThat(forA.at("/ordersValue90d").decimalValue()).isEqualByComparingTo("1000");
        assertThat(forB.at("/ordersCount90d").asInt()).isEqualTo(3);
        assertThat(forB.at("/ordersValue90d").decimalValue()).isEqualByComparingTo("30000");
        assertThat(forB.at("/averageOrderValue").decimalValue()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("a first-time requester: zeros, nulls, no past line, and just the request in the history")
    void firstTimeRequester() throws Exception {
        var buyer = s.newBuyer();
        var seller = s.newSeller();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                .header("Authorization", "Bearer " + seller.token())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"creditEnabled\":true,\"defaultCreditPeriodDays\":30,\"defaultGracePeriodDays\":5}"));
        long id = s.api.post(buyer.token(), "/api/v1/credit/requests", Map.of("supplierStoreId", seller.storeId(),
                "outletId", buyer.outletId(), "requestedLimit", "1000", "requestedDays", 30)).at("/data/id").asLong();

        var reply = context(seller.token(), seller.storeId(), id);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var d = reply.data();
        assertThat(d.at("/ordersCount90d").asInt()).isZero();
        assertThat(d.at("/ordersValue90d").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.at("/averageOrderValue").isNull()).isTrue();
        assertThat(d.at("/lastOrderDate").isNull()).isTrue();
        assertThat(d.at("/firstOrderDate").isNull()).isTrue();
        assertThat(d.at("/cancelledOrders90d").asInt()).isZero();
        assertThat(d.at("/previousOverdueCount").asInt()).isZero();
        assertThat(d.at("/pastLineStatus").isNull()).isTrue();
        assertThat(d.at("/history").size()).isEqualTo(1);
        assertThat(d.at("/history/0/event").asText()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("a rejected earlier round shows as the past line, with the supplier's reason")
    void pastRejectedLine() throws Exception {
        var buyer = s.newBuyer();
        var seller = s.newSeller();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                .header("Authorization", "Bearer " + seller.token())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"creditEnabled\":true,\"defaultCreditPeriodDays\":30,\"defaultGracePeriodDays\":5}"));
        long id = s.api.post(buyer.token(), "/api/v1/credit/requests", Map.of("supplierStoreId", seller.storeId(),
                "outletId", buyer.outletId(), "requestedLimit", "1000", "requestedDays", 30)).at("/data/id").asLong();
        e.call("POST", seller.token(), "/api/v1/credit/agreements/" + id + "/reject", null, Map.of("reason", "Too new"));
        rerequest(new Line(buyer, seller, id));

        var d = context(seller.token(), seller.storeId(), id).data();

        assertThat(d.at("/pastLineStatus").asText()).isEqualTo("REJECTED");
        boolean found = false;
        for (var item : d.at("/history")) {
            if (item.at("/event").asText().equals("REJECTED")) {
                found = true;
                assertThat(item.at("/note").asText()).isEqualTo("Too new");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("who may read it: owner, finance and store manager (CREDIT_REQUEST_VIEW); another store's owner, the restaurant and a user without the permission get 404")
    void permissionsAndIsolation() throws Exception {
        var line = s.creditLine("500000");
        var other = s.creditLine("500000");

        assertThat(context(line).status()).isEqualTo(200);
        assertThat(context(l.staff(line, "SUP_STORE_MANAGER"), line.seller().storeId(), line.agreementId()).status())
                .isEqualTo(200);
        assertThat(context(l.staff(line, "SUP_FINANCE_STAFF"), line.seller().storeId(), line.agreementId()).status())
                .isEqualTo(200);

        var refused = new java.util.LinkedHashMap<String, Reply>();
        refused.put("another store's owner on this store's path",
                context(other.seller().token(), line.seller().storeId(), line.agreementId()));
        refused.put("another store's owner on their own store with this agreement",
                context(other.seller().token(), other.seller().storeId(), line.agreementId()));
        refused.put("the restaurant", context(line.buyer().token(), line.seller().storeId(), line.agreementId()));
        refused.put("salesperson and operations staff (no credit permission)", context(l.staff(line, "SUP_SALESPERSON"), line.seller().storeId(), line.agreementId()));
        refused.put("operations staff",
                context(l.staff(line, "SUP_OPERATIONS_STAFF"), line.seller().storeId(), line.agreementId()));
        refused.put("the owner, with an agreement of another store",
                context(line.seller().token(), line.seller().storeId(), other.agreementId()));
        refused.put("unknown agreement", context(line.seller().token(), line.seller().storeId(), 999999999L));
        for (var r : refused.entrySet()) {
            assertThat(r.getValue().status()).describedAs(r.getKey() + " " + r.getValue().body()).isEqualTo(404);
        }
    }
}
