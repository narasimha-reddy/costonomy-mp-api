package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 credit rules: exact sub-rupee residuals on wallet repayment (M03/B5), the terms version on accept
 * (S12/B6/B8) and the event a manual reinstate publishes (B9). No nested classes, so every test runs under failsafe.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditRulesHardeningIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
    }

    private Reply pay(Line line, String amount, Long... invoiceIds) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("amount", amount);
        if (invoiceIds.length > 0) {
            body.put("invoiceIds", java.util.List.of(invoiceIds));
        }
        return s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(), body);
    }

    private String invoiceStatus(long id) {
        return jdbc.queryForObject("select status from credit_invoice where id = ?", String.class, id);
    }

    private Reply post(String token, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    // ── M03 / B5: sub-rupee residuals ────────────────────────────────────

    @Test
    @DisplayName("pay 999.50 of 1000, then the exact 0.50 left: 201 and the invoice is PAID")
    void exactResidualIsPayable() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "10", 100);
        s.topUp(line.buyer(), "5000.00");

        assertThat(pay(line, "999.50").status()).isEqualTo(201);
        assertThat(invoiceStatus(invoice)).isEqualTo("PARTIALLY_PAID");

        var last = pay(line, "0.50");
        assertThat(last.status()).describedAs(last.body().toString()).isEqualTo(201);
        assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
        assertThat(s.balance(line.buyer())).isEqualByComparingTo("4000.00");
    }

    @Test
    @DisplayName("0.50 on a fresh 1000 invoice is refused with the clear message, and nothing moves")
    void subRupeeThatDoesNotClearIsRefused() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "10", 100);
        s.topUp(line.buyer(), "5000.00");
        var before = s.snapshot(line);

        var reply = pay(line, "0.50");

        assertThat(reply.status()).describedAs(reply.body().toString()).isIn(400, 422);
        assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(reply.body().toString()).contains("Enter at least ₹1, or the exact remaining amount");
        assertThat(s.snapshot(line)).isEqualTo(before);
        assertThat(invoiceStatus(invoice)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("0.00 and negative amounts are refused")
    void zeroAndNegativeRefused() throws Exception {
        var line = s.creditLine("200000");
        s.invoice(line, "10", 100);
        s.topUp(line.buyer(), "5000.00");
        assertThat(pay(line, "0.00").status()).isIn(400, 422);
        assertThat(pay(line, "-0.50").status()).isIn(400, 422);
        assertThat(pay(line, "-5.00").status()).isIn(400, 422);
    }

    @Test
    @DisplayName("an exact sub-rupee amount targeting chosen invoices must equal THEIR outstanding")
    void exactAgainstChosenInvoices() throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "10", 100);
        long second = s.invoice(line, "10", 100);
        s.topUp(line.buyer(), "5000.00");
        assertThat(pay(line, "999.50", first).status()).isEqualTo(201);

        // 0.50 equals the sum of the whole agreement's outstanding? No: the second invoice still owes 1000.
        assertThat(pay(line, "0.50").status()).isIn(400, 422);
        // Against the first invoice only it is exactly what clears it.
        assertThat(pay(line, "0.50", first).status()).isEqualTo(201);
        assertThat(invoiceStatus(first)).isEqualTo("PAID");
        assertThat(invoiceStatus(second)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("an allocation across two invoices that leaves a 0.30 remainder on the last one leaves no residual")
    void allocationAcrossTwoInvoicesWithSubRupeeTail() throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "10", 100);
        long second = s.invoice(line, "10", 100);
        s.topUp(line.buyer(), "5000.00");
        assertThat(pay(line, "1999.70").status()).isEqualTo(201);
        assertThat(invoiceStatus(first)).isEqualTo("PAID");
        assertThat(invoiceStatus(second)).isEqualTo("PARTIALLY_PAID");

        var last = pay(line, "0.30");
        assertThat(last.status()).describedAs(last.body().toString()).isEqualTo(201);
        assertThat(invoiceStatus(second)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?", BigDecimal.class,
                line.agreementId())).isEqualByComparingTo("0");
    }

    // ── S12 / B6 / B8: terms version on accept ───────────────────────────

    /** A line approved on modified terms (so APPROVED, waiting for the restaurant). */
    private Line approvedModified() throws Exception {
        var buyer = s.newBuyer();
        var seller = s.newSeller();
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                .header("Authorization", "Bearer " + seller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("creditEnabled", true,
                        "defaultCreditPeriodDays", 30, "defaultGracePeriodDays", 5))));
        long agreementId = s.api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                "requestedLimit", "200000", "requestedDays", 30, "purpose", "PROCUREMENT")).at("/data/id").asLong();
        var approved = post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve",
                Map.of("approvedLimit", "100000", "creditPeriodDays", 15));
        assertThat(approved.data().get("status").asText()).isEqualTo("APPROVED");
        return new Line(buyer, seller, agreementId);
    }

    private int termsVersion(Line line) throws Exception {
        return s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId())
                .at("/data/termsVersion").asInt();
    }

    private Reply modify(Line line, String limit) throws Exception {
        return post(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/modify",
                Map.of("approvedLimit", limit, "creditPeriodDays", 20, "reason", "Changed again"));
    }

    @Test
    @DisplayName("a stale termsVersion on accept is 409 CREDIT_TERMS_CHANGED and nothing changes; the current one activates")
    void staleTermsVersionIsRefused() throws Exception {
        var line = approvedModified();
        int seen = termsVersion(line);
        assertThat(modify(line, "90000").status()).isEqualTo(200);
        int current = termsVersion(line);
        assertThat(current).isGreaterThan(seen);

        var stale = post(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/accept",
                Map.of("termsVersion", seen));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("CREDIT_TERMS_CHANGED");
        assertThat(stale.body().toString()).contains("The supplier changed the terms. Please review them again.");
        assertThat(jdbc.queryForObject("select status from credit_agreement where id = ?", String.class,
                line.agreementId())).isEqualTo("APPROVED");

        var ok = post(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/accept",
                Map.of("termsVersion", current));
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(200);
        assertThat(ok.data().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(ok.data().get("termsVersion").asInt()).isEqualTo(current);
    }

    @Test
    @DisplayName("accept without a body still works (the current mobile client)")
    void legacyAcceptWithoutBody() throws Exception {
        var line = approvedModified();
        var ok = post(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/accept", null);
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(200);
        assertThat(ok.data().get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("accept with an empty object is the legacy path too")
    void legacyAcceptWithEmptyObject() throws Exception {
        var line = approvedModified();
        var ok = post(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/accept", Map.of());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("modify keeps working on APPROVED and ACTIVE, bumping the version each time; terminal is refused")
    void modifyBumpsVersion() throws Exception {
        var line = approvedModified();
        int v = termsVersion(line);
        assertThat(modify(line, "90000").status()).isEqualTo(200);
        assertThat(termsVersion(line)).isEqualTo(v + 1);
        assertThat(modify(line, "80000").status()).isEqualTo(200);
        assertThat(termsVersion(line)).isEqualTo(v + 2);

        post(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/accept", null);
        assertThat(modify(line, "70000").status()).isEqualTo(200);
        assertThat(termsVersion(line)).isEqualTo(v + 3);

        jdbc.update("update credit_agreement set status = 'CLOSED' where id = ?", line.agreementId());
        assertThat(modify(line, "60000").status()).isEqualTo(409);
    }

    // ── B9: manual reinstate tells the restaurant ────────────────────────

    @Test
    @DisplayName("a supplier's manual reinstate publishes exactly one CreditReinstated with the system payload")
    void manualReinstatePublishesEvent() throws Exception {
        var line = s.creditLine("200000");
        post(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/suspend",
                Map.of("reason", "Account under review"));
        assertThat(count(line)).isZero();

        var reply = post(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/reinstate",
                Map.of());
        assertThat(reply.status()).isEqualTo(200);
        assertThat(count(line)).isEqualTo(1);
        var node = json.readTree(jdbc.queryForObject("select payload from outbox_event where event_type = "
                + "'CreditReinstated' and aggregate_id = ?", String.class, line.agreementId()));
        assertThat(node.get("creditAgreementId").asLong()).isEqualTo(line.agreementId());
        assertThat(node.get("outletId").asLong()).isEqualTo(line.buyer().outletId());
        assertThat(node.get("supplierStoreId").asLong()).isEqualTo(line.seller().storeId());
        assertThat(node.get("supplierName").asText()).isNotBlank();

        // Reinstating an ACTIVE line again is a no-op and publishes nothing more.
        post(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/reinstate", Map.of());
        assertThat(count(line)).isEqualTo(1);
    }

    private int count(Line line) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = 'CreditReinstated' "
                + "and aggregate_id = ?", Integer.class, line.agreementId());
    }
}
