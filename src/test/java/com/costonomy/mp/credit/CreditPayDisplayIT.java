package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the apps read once credit can be paid online (D-199): the summary's {@code onlinePayEnabled} switch, and the
 * supplier's payout and payment rows saying the money arrived through Mandi. The online repayment is made through
 * the wallet path and then relabelled in SQL, because these reads only look at the stored source and method.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "costonomy.mp.credit.wallet-repay.enabled=true",
        "costonomy.mp.credit.online-pay.enabled=true"})
class CreditPayDisplayIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.costonomy.mp.credit.service.CreditAgreementService agreementService;

    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
    }

    private Reply get(String token, String path) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private JsonNode ok(String token, String path) throws Exception {
        var reply = get(token, path);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    /** One wallet repayment, then relabelled as an online UPI one; returns its repayment id. */
    private long onlineRepayment(Line line, long invoiceId, boolean online) throws Exception {
        s.topUp(line.buyer(), "20000.00");
        var reply = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", java.util.List.of(invoiceId)));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        long repaymentId = reply.data().get("repaymentId").asLong();
        if (online) {
            jdbc.update("update credit_repayment set source = 'ONLINE' where id = ?", repaymentId);
            jdbc.update("update credit_payment set source = 'ONLINE', method = 'UPI' where credit_repayment_id = ?",
                    repaymentId);
        }
        return repaymentId;
    }

    private JsonNode payoutOf(Line line, long repaymentId) throws Exception {
        var data = ok(line.seller().token(),
                "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/payouts");
        for (JsonNode item : data.get("items")) {
            if (item.get("repaymentId").asLong() == repaymentId) {
                return item;
            }
        }
        throw new AssertionError("repayment " + repaymentId + " not in " + data);
    }

    @Test
    @DisplayName("the summary says online pay is on when the switch is on")
    void summaryOn() throws Exception {
        var line = s.creditLine("200000");
        var data = ok(line.buyer().token(), "/api/v1/outlets/" + line.buyer().outletId() + "/credit/summary");
        assertThat(data.get("onlinePayEnabled").isBoolean()).isTrue();
        assertThat(data.get("onlinePayEnabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the summary says online pay is off when the switch is off")
    void summaryOff() throws Exception {
        var line = s.creditLine("200000");
        org.springframework.test.util.ReflectionTestUtils.setField(agreementService, "onlinePayEnabled", false);
        try {
            var data = ok(line.buyer().token(), "/api/v1/outlets/" + line.buyer().outletId() + "/credit/summary");
            assertThat(data.get("onlinePayEnabled").isBoolean()).isTrue();
            assertThat(data.get("onlinePayEnabled").asBoolean()).isFalse();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(agreementService, "onlinePayEnabled", true);
        }
    }

    @Test
    @DisplayName("an online payout reads source ONLINE with its method; a wallet payout reads WALLET and no method")
    void payoutSourceAndMethod() throws Exception {
        var line = s.creditLine("200000");
        long online = onlineRepayment(line, s.invoice(line, "65", 100), true);
        long wallet = onlineRepayment(line, s.invoice(line, "40", 100), false);

        var onlineRow = payoutOf(line, online);
        assertThat(onlineRow.get("source").asText()).isEqualTo("ONLINE");
        assertThat(onlineRow.get("paymentMethod").asText()).isEqualTo("UPI");

        var walletRow = payoutOf(line, wallet);
        assertThat(walletRow.get("source").asText()).isEqualTo("WALLET");
        assertThat(walletRow.get("paymentMethod").isNull()).isTrue();

        long payoutId = onlineRow.get("payoutId").asLong();
        var one = ok(line.seller().token(),
                "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/payouts/" + payoutId);
        assertThat(one.get("source").asText()).isEqualTo("ONLINE");
        assertThat(one.get("paymentMethod").asText()).isEqualTo("UPI");
    }

    @Test
    @DisplayName("the payment feed filters by source=ONLINE and shows the method, not a provider id")
    void paymentFeedOnline() throws Exception {
        var line = s.creditLine("200000");
        onlineRepayment(line, s.invoice(line, "65", 100), true);
        onlineRepayment(line, s.invoice(line, "40", 100), false);

        var base = "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/payments";
        var online = ok(line.seller().token(), base + "?source=ONLINE");
        assertThat(online.get("items")).hasSize(1);
        var item = online.get("items").get(0);
        assertThat(item.get("source").asText()).isEqualTo("ONLINE");
        assertThat(item.get("method").asText()).isEqualTo("UPI");

        assertThat(ok(line.seller().token(), base).get("items")).hasSize(2);
        assertThat(ok(line.seller().token(), base + "?source=WALLET").get("items")).hasSize(1);
    }
}
