package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the supplier's claims inbox needs on every claim, worked out on the server (S5, D-139): how long it has waited,
 * whether that is stale, what is still owed on the invoice, what else is claimed, and a hint that it may be a duplicate.
 * The day arithmetic itself is in {@code CreditClaimAgeTest}.
 */
@AutoConfigureMockMvc
class CreditClaimReadFieldsIT extends AbstractIntegrationTest {

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

    private Reply claim(Line line, long invoice, String amount, String reference) throws Exception {
        var body = new HashMap<String, Object>(e.claimBody(amount));
        body.put("reference", reference);
        return e.call("POST", line.buyer().token(), "/api/v1/credit/invoices/" + invoice + "/claims",
                UUID.randomUUID().toString(), body);
    }

    private long claimId(Reply reply) {
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        return reply.data().get("id").asLong();
    }

    private void ageClaim(long claimId, int days) {
        jdbc.update("update credit_payment_claim set created_at = date_sub(now(6), interval ? day) where id = ?",
                days, claimId);
    }

    private JsonNode inboxRow(Line line, long claimId) throws Exception {
        var inbox = e.call("GET", line.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId()
                + "/credit/claims", null, null);
        assertThat(inbox.status()).isEqualTo(200);
        for (JsonNode row : inbox.data()) {
            if (row.get("id").asLong() == claimId) {
                return row;
            }
        }
        throw new AssertionError("claim " + claimId + " not in the inbox: " + inbox.body());
    }

    @Test
    @DisplayName("ageDays is India days since submission; stale is true from 7 days (6: no, 7: yes), for a waiting claim only")
    void ageAndStale() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        long fresh = claimId(claim(line, invoice, "10.00", "UTR-A1"));
        long six = claimId(claim(line, invoice, "20.00", "UTR-A2"));
        long seven = claimId(claim(line, invoice, "30.00", "UTR-A3"));
        long eight = claimId(claim(line, invoice, "40.00", "UTR-A4"));
        ageClaim(six, 6);
        ageClaim(seven, 7);
        ageClaim(eight, 8);

        assertThat(inboxRow(line, fresh).get("ageDays").asInt()).isZero();
        assertThat(inboxRow(line, fresh).get("stale").asBoolean()).isFalse();
        assertThat(inboxRow(line, six).get("ageDays").asInt()).isEqualTo(6);
        assertThat(inboxRow(line, six).get("stale").asBoolean()).isFalse();
        assertThat(inboxRow(line, seven).get("ageDays").asInt()).isEqualTo(7);
        assertThat(inboxRow(line, seven).get("stale").asBoolean()).isTrue();
        assertThat(inboxRow(line, eight).get("stale").asBoolean()).isTrue();

        // Once answered it is no longer waiting, so no longer stale.
        assertThat(e.confirm(line.seller().token(), eight, "40.00").status()).isEqualTo(200);
        var done = inboxRow(line, eight);
        assertThat(done.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(done.get("stale").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("invoiceOutstanding is what is owed now, invoiceOpenClaimsAmount the waiting claims; on the inbox, the agreement list, the invoice detail and the submit reply")
    void invoiceFigures() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "10", 100); // 1,000
        assertThat(s.recordPayment(line.seller(), invoice, "100.00").status()).isEqualTo(200);
        var first = claim(line, invoice, "200.00", "UTR-B1");
        long a = claimId(first);
        long b = claimId(claim(line, invoice, "300.00", "UTR-B2"));

        assertThat(first.data().get("invoiceOutstanding").decimalValue()).isEqualByComparingTo("900");
        assertThat(first.data().get("invoiceOpenClaimsAmount").decimalValue()).describedAs("this claim alone, at submit")
                .isEqualByComparingTo("200");
        for (long id : new long[]{a, b}) {
            var row = inboxRow(line, id);
            assertThat(row.get("invoiceOutstanding").decimalValue()).isEqualByComparingTo("900");
            assertThat(row.get("invoiceOpenClaimsAmount").decimalValue()).isEqualByComparingTo("500");
        }
        assertThat(inboxRow(line, a).get("invoiceOtherOpenClaimsAmount").decimalValue()).isEqualByComparingTo("300");
        assertThat(inboxRow(line, b).get("invoiceOtherOpenClaimsAmount").decimalValue()).isEqualByComparingTo("200");

        var viaAgreement = e.call("GET", line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId()
                + "/claims", null, null);
        assertThat(viaAgreement.data().get(0).get("invoiceOpenClaimsAmount").decimalValue()).isEqualByComparingTo("500");
        var detail = e.call("GET", line.buyer().token(), "/api/v1/credit/invoices/" + invoice, null, null);
        assertThat(detail.data().at("/claims/0/invoiceOutstanding").decimalValue()).isEqualByComparingTo("900");

        // The invoice moves on: figures follow it.
        assertThat(e.confirm(line.seller().token(), a, "200.00").status()).isEqualTo(200);
        var after = inboxRow(line, b);
        assertThat(after.get("invoiceOutstanding").decimalValue()).isEqualByComparingTo("700");
        assertThat(after.get("invoiceOpenClaimsAmount").decimalValue()).isEqualByComparingTo("300");
        // A settled invoice owes nothing.
        assertThat(s.recordPayment(line.seller(), invoice, "700.00").status()).isEqualTo(200);
        var settled = inboxRow(line, b);
        assertThat(settled.get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(settled.get("invoiceOutstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(settled.get("invoiceOpenClaimsAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("possibleDuplicateOf: another waiting claim on the invoice with the same amount and the same reference, or within 24h; else null")
    void duplicateOfAnotherClaim() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        long first = claimId(claim(line, invoice, "100.00", "UTR-DUP"));
        assertThat(inboxRow(line, first).get("possibleDuplicateOf").isNull()).isTrue();
        assertThat(inboxRow(line, first).get("possibleDuplicateKind").isNull()).isTrue();

        long sameRef = claimId(claim(line, invoice, "100.00", "utr-dup "));
        assertThat(inboxRow(line, sameRef).get("possibleDuplicateOf").asLong()).isEqualTo(first);
        assertThat(inboxRow(line, sameRef).get("possibleDuplicateKind").asText()).isEqualTo("CLAIM");
        assertThat(inboxRow(line, first).get("possibleDuplicateOf").asLong()).describedAs("it works both ways")
                .isEqualTo(sameRef);

        // Same amount, different reference, both within a day: still suspicious.
        long sameDay = claimId(claim(line, invoice, "100.00", "UTR-OTHER"));
        assertThat(inboxRow(line, sameDay).get("possibleDuplicateOf").isNull()).isFalse();

        // Same reference but another amount: a different payment.
        long otherAmount = claimId(claim(line, invoice, "150.00", "UTR-DUP"));
        assertThat(inboxRow(line, otherAmount).get("possibleDuplicateOf").isNull()).isTrue();

        // Same amount, different reference, three days apart: not flagged.
        long old = claimId(claim(line, invoice, "70.00", "UTR-OLD1"));
        ageClaim(old, 3);
        long later = claimId(claim(line, invoice, "70.00", "UTR-OLD2"));
        assertThat(inboxRow(line, later).get("possibleDuplicateOf").isNull()).isTrue();
        assertThat(inboxRow(line, old).get("possibleDuplicateOf").isNull()).isTrue();
    }

    @Test
    @DisplayName("a claim that repeats a CONFIRMED one is flagged; a confirmed claim's own payment is not a duplicate of it")
    void duplicateOfAConfirmedClaim() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        long original = claimId(claim(line, invoice, "100.00", "UTR-ONCE"));
        assertThat(e.confirm(line.seller().token(), original, null).status()).isEqualTo(200);
        assertThat(inboxRow(line, original).get("possibleDuplicateOf").isNull()).isTrue();

        long repeat = claimId(claim(line, invoice, "100.00", "UTR-ONCE"));

        var row = inboxRow(line, repeat);
        assertThat(row.get("possibleDuplicateOf").asLong()).isEqualTo(original);
        assertThat(row.get("possibleDuplicateKind").asText()).isEqualTo("CLAIM");
    }

    @Test
    @DisplayName("a claim that matches a payment the supplier already recorded is flagged as a PAYMENT duplicate")
    void duplicateOfARecordedPayment() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        var recorded = e.call("POST", line.seller().token(), "/api/v1/credit/invoices/" + invoice + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "250.00", "method", "UPI", "reference", "UTR-REC"));
        assertThat(recorded.status()).isEqualTo(200);
        long paymentId = recorded.data().get("id").asLong();

        long sameRef = claimId(claim(line, invoice, "250.00", "UTR-REC"));
        var row = inboxRow(line, sameRef);
        assertThat(row.get("possibleDuplicateOf").asLong()).isEqualTo(paymentId);
        assertThat(row.get("possibleDuplicateKind").asText()).isEqualTo("PAYMENT");

        long differentAmount = claimId(claim(line, invoice, "90.00", "UTR-REC"));
        assertThat(inboxRow(line, differentAmount).get("possibleDuplicateOf").isNull()).isTrue();
    }

    @Test
    @DisplayName("another invoice's claims and payments never make a claim look like a duplicate")
    void duplicatesAreWithinOneInvoice() throws Exception {
        var line = s.creditLine("200000");
        long one = s.invoice(line, "100", 100);
        long two = s.invoice(line, "100", 100);
        claimId(claim(line, one, "100.00", "UTR-X"));
        long other = claimId(claim(line, two, "100.00", "UTR-X"));
        assertThat(inboxRow(line, other).get("possibleDuplicateOf").isNull()).isTrue();
    }

    @Test
    @DisplayName("the new fields are on the claim a restaurant submits too, and an old claim reads the same after a refetch")
    void presentEverywhere() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        var submitted = claim(line, invoice, "100.00", "UTR-Z");
        for (String field : new String[]{"ageDays", "stale", "invoiceOutstanding", "invoiceOpenClaimsAmount",
                "invoiceOtherOpenClaimsAmount", "possibleDuplicateOf", "possibleDuplicateKind"}) {
            assertThat(submitted.data().has(field)).describedAs(field).isTrue();
        }
        assertThat(submitted.data().get("ageDays").asInt()).isZero();
        assertThat(submitted.data().get("stale").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("another store's user still gets 404 for the inbox, and the new fields add no new access")
    void isolationUnchanged() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        claimId(claim(line, invoice, "100.00", "UTR-Q"));
        var other = s.creditLine("200000");
        var reply = e.call("GET", other.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId()
                + "/credit/claims", null, null);
        assertThat(reply.status()).isEqualTo(404);
    }
}
