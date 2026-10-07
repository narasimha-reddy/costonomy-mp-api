package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing a credit line (B9a, SU10-SU12, RQ05, RQ06, D-165): refused while anything is owed or held, otherwise CLOSED,
 * the restaurant told, and a fresh request still possible.
 */
@AutoConfigureMockMvc
class CreditCloseLineIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
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

    private void offerCredit(CreditWalletSupport.Seller seller) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                .header("Authorization", "Bearer " + seller.token())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"creditEnabled\":true,\"defaultCreditPeriodDays\":30,\"defaultGracePeriodDays\":5}"));
    }

    private Reply close(String token, long agreementId, String reason) throws Exception {
        return e.call("POST", token, "/api/v1/credit/agreements/" + agreementId + "/close", null,
                Map.of("reason", reason));
    }

    private Reply close(Line line, String reason) throws Exception {
        return close(line.seller().token(), line.agreementId(), reason);
    }

    private Reply rerequest(Line line) throws Exception {
        return e.call("POST", line.buyer().token(), "/api/v1/credit/requests", null,
                Map.of("supplierStoreId", line.seller().storeId(), "outletId", line.buyer().outletId(),
                        "requestedLimit", "80000", "requestedDays", 15));
    }

    @Test
    @DisplayName("SU10: a line with nothing owed or held closes; the restaurant is told once, in-app and push, never SMS; audited")
    void closesAnEmptyLine() throws Exception {
        var line = s.creditLine("200000");
        l.registerDevice(line.buyer().token());

        var reply = close(line, "No longer trading with them");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/status").asText()).isEqualTo("CLOSED");
        assertThat(reply.data().at("/canFund").asBoolean()).isFalse();
        assertThat(l.status(line.agreementId())).isEqualTo("CLOSED");
        assertThat(l.column(line.agreementId(), "closed_at")).isNotNull();
        assertThat(l.audits("CREDIT_CLOSED", line.agreementId())).isEqualTo(1);
        assertThat(l.events("CreditClosed", line.agreementId())).isEqualTo(1);
        var payload = json.readTree(l.payload("CreditClosed", line.agreementId()));
        assertThat(payload.at("/outletId").asLong()).isEqualTo(line.buyer().outletId());
        assertThat(payload.at("/supplierStoreId").asLong()).isEqualTo(line.seller().storeId());
        assertThat(payload.at("/reason").asText()).isEqualTo("No longer trading with them");

        l.relayEvents("CreditClosed", line.agreementId());
        var sent = l.notificationsFor("CreditClosed", line.agreementId());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("audience")).isEqualTo("OUTLET");
        assertThat((String) sent.get(0).get("body")).contains("ABC Foods").contains("No longer trading with them");
        assertThat(l.deliveries("CreditClosed", line.agreementId(), "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditClosed", line.agreementId(), "SMS")).isZero();
    }

    @Test
    @DisplayName("SU11: ₹1 owed: 409 and nothing changes (no state change, no audit, no event)")
    void refusedWhileSomethingIsOwed() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "1", 1);
        var before = s.snapshot(line);

        var reply = close(line, "Closing");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(reply.body().at("/error/message").asText()).containsIgnoringCase("owed");
        assertThat(l.status(line.agreementId())).isEqualTo("ACTIVE");
        assertThat(l.column(line.agreementId(), "closed_at")).isNull();
        assertThat(l.audits("CREDIT_CLOSED", line.agreementId())).isZero();
        assertThat(l.events("CreditClosed", line.agreementId())).isZero();
        assertThat(s.snapshot(line)).isEqualTo(before);

        // Once it is paid, it closes.
        assertThat(s.recordPayment(line.seller(), invoice, "1.00").status()).isEqualTo(200);
        assertThat(close(line, "Closing").status()).isEqualTo(200);
        assertThat(l.status(line.agreementId())).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("SU12: credit held for an order in flight: 409; released, it closes")
    void refusedWhileCreditIsOnHold() throws Exception {
        var line = s.creditLine("200000");
        jdbc.update("update credit_agreement set reserved_amount = 500 where id = ?", line.agreementId());

        var reply = close(line, "Closing");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(reply.body().at("/error/message").asText()).containsIgnoringCase("hold");
        assertThat(l.status(line.agreementId())).isEqualTo("ACTIVE");
        assertThat(l.audits("CREDIT_CLOSED", line.agreementId())).isZero();

        jdbc.update("update credit_agreement set reserved_amount = 0 where id = ?", line.agreementId());
        assertThat(close(line, "Closing").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a SUSPENDED line with nothing owed closes; a REQUESTED or APPROVED one does not (reject it instead)")
    void closeFromSuspendedButNotFromRequestedOrApproved() throws Exception {
        var suspended = s.creditLine("200000");
        assertThat(e.suspend(suspended, "Review").status()).isEqualTo(200);
        var closed = close(suspended, "Done");
        assertThat(closed.status()).describedAs(closed.body().toString()).isEqualTo(200);
        assertThat(l.column(suspended.agreementId(), "suspension_reason")).isNull();
        assertThat(l.column(suspended.agreementId(), "suspension_source")).isNull();

        var offer = s.creditLine("200000", Map.of("approvedLimit", "100000"));
        assertThat(l.status(offer.agreementId())).isEqualTo("APPROVED");
        var refused = close(offer, "Done");
        assertThat(refused.status()).describedAs(refused.body().toString()).isEqualTo(409);
        assertThat(l.status(offer.agreementId())).isEqualTo("APPROVED");

        var buyer = s.newBuyer();
        var seller = s.newSeller();
        offerCredit(seller);
        long requested = s.api.post(buyer.token(), "/api/v1/credit/requests", Map.of("supplierStoreId", seller.storeId(),
                "outletId", buyer.outletId(), "requestedLimit", "1000", "requestedDays", 30)).at("/data/id").asLong();
        var waiting = close(seller.token(), requested, "Done");
        assertThat(waiting.status()).describedAs(waiting.body().toString()).isEqualTo(409);
        assertThat(l.status(requested)).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("closing twice answers the second as a retry: still 200, one audit row, one event")
    void closingTwiceIsARetry() throws Exception {
        var line = s.creditLine("200000");
        assertThat(close(line, "Done").status()).isEqualTo(200);
        var again = close(line, "Done");
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);
        assertThat(again.data().at("/status").asText()).isEqualTo("CLOSED");
        assertThat(l.audits("CREDIT_CLOSED", line.agreementId())).isEqualTo(1);
        assertThat(l.events("CreditClosed", line.agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a reason is required")
    void reasonRequired() throws Exception {
        var line = s.creditLine("200000");
        assertThat(close(line, " ").status()).isEqualTo(400);
        assertThat(e.call("POST", line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/close",
                null, Map.of()).status()).isEqualTo(400);
        assertThat(l.status(line.agreementId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a CLOSED line accepts no orders: refused as not active, nothing reserved or invoiced")
    void closedLineTakesNoOrders() throws Exception {
        var line = s.creditLine("200000");
        long intent = e.answeredRequest(line, "100", 10);
        assertThat(close(line, "Done").status()).isEqualTo(200);

        var refused = e.orderOnCredit(line.buyer(), intent);

        assertThat(refused.status()).describedAs(refused.body().toString()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("CREDIT_AGREEMENT_NOT_ACTIVE");
        var row = e.agreementRow(line.agreementId());
        assertThat(e.dec(row, "reserved_amount")).isEqualByComparingTo("0");
        assertThat(e.dec(row, "utilized_amount")).isEqualByComparingTo("0");
        assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ?",
                line.agreementId())).isZero();
        // The restaurant still reads the closed line.
        var seen = e.call("GET", line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId(), null, null);
        assertThat(seen.data().at("/status").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("RQ06: after CLOSED with ₹0 owed the restaurant can ask again, on the same line; the supplier can approve it")
    void restaurantCanRequestAgainAfterClose() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "10", 10);
        assertThat(s.recordPayment(line.seller(), invoice, "100.00").status()).isEqualTo(200);
        assertThat(close(line, "Done").status()).isEqualTo(200);
        long requestsBefore = e.count("select count(*) from credit_request where credit_agreement_id = ?",
                line.agreementId());

        var again = rerequest(line);

        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);
        assertThat(again.data().at("/id").asLong()).describedAs("one line per outlet and store").isEqualTo(line.agreementId());
        assertThat(again.data().at("/status").asText()).isEqualTo("REQUESTED");
        assertThat(l.column(line.agreementId(), "closed_at")).describedAs("a new round is not closed").isNull();
        assertThat(e.count("select count(*) from credit_request where credit_agreement_id = ?", line.agreementId()))
                .isEqualTo(requestsBefore + 1);

        var approved = s.api.post(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/approve",
                Map.of());
        assertThat(approved.at("/data/status").asText()).isEqualTo("ACTIVE");
        assertThat(approved.at("/data/approvedLimit").decimalValue()).isEqualByComparingTo("80000");
    }

    @Test
    @DisplayName("RQ05: after REJECTED the restaurant can ask again too")
    void requestAgainAfterRejected() throws Exception {
        var buyer = s.newBuyer();
        var seller = s.newSeller();
        offerCredit(seller);
        long id = s.api.post(buyer.token(), "/api/v1/credit/requests", Map.of("supplierStoreId", seller.storeId(),
                "outletId", buyer.outletId(), "requestedLimit", "1000", "requestedDays", 30)).at("/data/id").asLong();
        assertThat(e.call("POST", seller.token(), "/api/v1/credit/agreements/" + id + "/reject", null,
                Map.of("reason", "Not now")).status()).isEqualTo(200);

        var again = rerequest(new Line(buyer, seller, id));

        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);
        assertThat(again.data().at("/status").asText()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("RQ06: a CLOSED line that somehow still owes ₹1 cannot be requested again")
    void requestAgainRefusedWhenSomethingIsStillOwed() throws Exception {
        var line = s.creditLine("200000");
        assertThat(close(line, "Done").status()).isEqualTo(200);
        jdbc.update("update credit_agreement set utilized_amount = 1 where id = ?", line.agreementId());

        var again = rerequest(line);

        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(409);
        assertThat(l.status(line.agreementId())).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("who may close: owner, admin and finance can; a store manager (collect only), a salesperson, another supplier and the restaurant get 404")
    void permissions() throws Exception {
        var line = s.creditLine("200000");
        var refused = new java.util.LinkedHashMap<String, String>();
        refused.put("store manager", l.staff(line, "SUP_STORE_MANAGER"));
        refused.put("salesperson", l.staff(line, "SUP_SALESPERSON"));
        refused.put("another store's owner", s.creditLine("200000").seller().token());
        refused.put("the restaurant", line.buyer().token());
        for (var who : refused.entrySet()) {
            var reply = close(who.getValue(), line.agreementId(), "Done");
            assertThat(reply.status()).describedAs(who.getKey() + " " + reply.body()).isEqualTo(404);
        }
        assertThat(l.status(line.agreementId())).isEqualTo("ACTIVE");
        assertThat(l.audits("CREDIT_CLOSED", line.agreementId())).isZero();

        var admin = s.creditLine("200000");
        assertThat(close(l.staff(admin, "SUP_ADMIN"), admin.agreementId(), "Done").status()).isEqualTo(200);
        var finance = s.creditLine("200000");
        assertThat(close(l.staff(finance, "SUP_FINANCE_STAFF"), finance.agreementId(), "Done").status()).isEqualTo(200);
        assertThat(close(line, "Done").status()).isEqualTo(200);
        assertThat(close(s.creditLine("200000").seller().token(), 999999999L, "Done").status()).isEqualTo(404);
    }
}
