package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Scratch smoke test of WP-A (not part of the contract's suites; WP-D owns those). */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPaySmokeIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private MockPaymentProvider provider;
    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
    }

    private Reply call(String method, String token, String path, String key, Object body) throws Exception {
        var request = "GET".equals(method) ? MockMvcRequestBuilders.get(path) : MockMvcRequestBuilders.post(path);
        request.header("Authorization", "Bearer " + token);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private Map<String, Object> item(long id, String outstanding) {
        return Map.of("invoiceId", id, "outstanding", new BigDecimal(outstanding));
    }

    @Test
    void onlineAcrossTwoSuppliersThenSkipAndWallet() throws Exception {
        var a = s.creditLine("200000");
        var b = s.creditLine(a.buyer(), "200000", Map.of());
        long a1 = s.invoice(a, "65", 100);
        long a2 = s.invoice(a, "40", 100);
        long b1 = s.invoice(b, "30", 100);
        s.age(b1, 10);
        String t = a.buyer().token();
        long outlet = a.buyer().outletId();
        String base = "/api/v1/outlets/" + outlet + "/credit/";

        var payable = call("GET", t, base + "payable-invoices", null, null);
        assertThat(payable.status()).describedAs(payable.body().toString()).isEqualTo(200);
        System.out.println("PAYABLE " + payable.body());
        assertThat(payable.data().at("/suppliers/0/agreementId").asLong()).isEqualTo(b.agreementId());

        var items = List.of(item(a1, "6500.00"), item(a2, "4000.00"), item(b1, "3000.00"));
        var quote = call("POST", t, base + "payments/quote", null, Map.of("items", items));
        assertThat(quote.status()).describedAs(quote.body().toString()).isEqualTo(200);
        assertThat(quote.data().get("amount").decimalValue()).isEqualByComparingTo("13500");
        assertThat(quote.data().at("/methods/2/unavailableReason").asText()).isEqualTo("WALLET_SHORT");

        String key = UUID.randomUUID().toString();
        var created = call("POST", t, base + "payments", key, Map.of("items", items, "method", "UPI"));
        assertThat(created.status()).describedAs(created.body().toString()).isEqualTo(201);
        long attemptId = created.data().get("attemptId").asLong();
        String order = created.data().get("razorpayOrderId").asText();
        assertThat(created.data().get("status").asText()).isEqualTo("CREATED");
        assertThat(created.data().get("keyId").asText()).isNotBlank();
        var again = call("POST", t, base + "payments", UUID.randomUUID().toString(), Map.of("items", items, "method", "CARD"));
        assertThat(again.data().get("reopened").asBoolean()).isTrue();
        assertThat(again.data().get("razorpayOrderId").asText()).isEqualTo(order);

        // wrong outstanding
        var changed = call("POST", t, base + "payments", UUID.randomUUID().toString(),
                Map.of("items", List.of(item(a1, "6400.00")), "method", "UPI"));
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.code()).isEqualTo("CREDIT_PAY_SELECTION_CHANGED");

        var payment = provider.completeCheckout(order, "upi");
        var verify = call("POST", t, base + "payments/" + attemptId + "/verify", null,
                Map.of("razorpayPaymentId", payment.providerPaymentId(), "razorpaySignature", MockPaymentProvider.TEST_SIGNATURE));
        assertThat(verify.status()).describedAs(verify.body().toString()).isEqualTo(200);
        System.out.println("VERIFY " + verify.body());
        assertThat(verify.data().get("status").asText()).isEqualTo("PAID");
        var verify2 = call("POST", t, base + "payments/" + attemptId + "/verify", null,
                Map.of("razorpayPaymentId", payment.providerPaymentId(), "razorpaySignature", MockPaymentProvider.TEST_SIGNATURE));
        assertThat(verify2.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from credit_repayment where credit_payment_attempt_id = ?", Integer.class, attemptId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from credit_repayment_payout p join credit_repayment r on r.id = p.credit_repayment_id where r.credit_payment_attempt_id = ?", Integer.class, attemptId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from credit_invoice where status = 'PAID' and id in (?,?,?)", Integer.class, a1, a2, b1)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(utilized_amount) from credit_agreement where id in (?,?)", BigDecimal.class, a.agreementId(), b.agreementId())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from credit_payment cp join credit_repayment r on r.id = cp.credit_repayment_id where cp.source = 'ONLINE' and cp.method = 'UPI' and r.credit_payment_attempt_id = ?", Integer.class, attemptId)).isEqualTo(3);

        // skip: a new selection where the supplier records a part payment before the capture
        long c1 = s.invoice(a, "10", 100);
        long c2 = s.invoice(b, "20", 100);
        var sel2 = List.of(item(c1, "1000.00"), item(c2, "2000.00"));
        var created2 = call("POST", t, base + "payments", UUID.randomUUID().toString(), Map.of("items", sel2, "method", "UPI"));
        long attempt2 = created2.data().get("attemptId").asLong();
        assertThat(s.recordPayment(a.seller(), c1, "400.00").status()).isIn(200, 201);
        var pay2 = provider.completeCheckout(created2.data().get("razorpayOrderId").asText(), "upi");
        var verify3 = call("POST", t, base + "payments/" + attempt2 + "/verify", null,
                Map.of("razorpayPaymentId", pay2.providerPaymentId(), "razorpaySignature", MockPaymentProvider.TEST_SIGNATURE));
        System.out.println("VERIFY3 " + verify3.body());
        assertThat(verify3.data().get("status").asText()).isEqualTo("PAID");
        assertThat(verify3.data().get("refundAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(verify3.data().get("refundState").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select skip_reason from credit_payment_attempt_invoice where attempt_id = ? and credit_invoice_id = ?", String.class, attempt2, c1)).isEqualTo("AMOUNT_CHANGED");

        // wallet, two suppliers
        long a3 = s.invoice(a, "10", 100);
        long b3 = s.invoice(b, "20", 100);
        s.topUp(a.buyer(), "10000.00");
        var items3 = List.of(item(a3, "1000.00"), item(b3, "2000.00"));
        var w = call("POST", t, base + "payments", UUID.randomUUID().toString(), Map.of("items", items3, "method", "WALLET"));
        System.out.println("WALLET " + w.body());
        assertThat(w.status()).isEqualTo(201);
        assertThat(w.data().get("status").asText()).isEqualTo("PAID");
        assertThat(w.data().get("walletBalanceAfter").decimalValue()).isEqualByComparingTo("7000");
        // This attempt's own wallet debits (the table is shared with every IT that ran before in the same database).
        assertThat(jdbc.queryForObject("select count(*) from credit_repayment where credit_payment_attempt_id = ? "
                + "and wallet_transaction_id is not null", Integer.class, w.data().get("attemptId").asLong())).isEqualTo(2);
        var got = call("GET", t, base + "payments/" + w.data().get("attemptId").asLong(), null, null);
        assertThat(got.data().get("status").asText()).isEqualTo("PAID");
    }
}
