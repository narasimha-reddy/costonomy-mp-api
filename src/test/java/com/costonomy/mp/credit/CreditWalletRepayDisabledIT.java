package com.costonomy.mp.credit;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wallet repayment with the feature flag left at its default, off (D-153): a restaurant that holds CREDIT_REPAY and
 * has the money is still refused, and nothing moves. A separate class because the flag is a context property.
 */
@AutoConfigureMockMvc
class CreditWalletRepayDisabledIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Value("${costonomy.mp.credit.wallet-repay.enabled}") private boolean flag;

    @Test
    @DisplayName("flag off: 403, nothing is debited, no repayment or payment row, the invoice is untouched")
    void flagOffReturns403AndMovesNothing() throws Exception {
        var s = new CreditWalletSupport(mvc, json, jdbc);
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        var before = s.snapshot(line);

        var reply = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", java.util.List.of(invoice)));

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.code()).isEqualTo("FORBIDDEN");
        var after = s.snapshot(line);
        assertThat(after.wallet()).isEqualByComparingTo(before.wallet()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(after.walletRows()).isEqualTo(before.walletRows());
        assertThat(after.repayments()).isZero();
        assertThat(after.creditPayments()).isEqualTo(before.creditPayments());
        assertThat(after.invoices()).isEqualTo(before.invoices());
        assertThat(after.utilized()).isEqualByComparingTo(before.utilized());
        assertThat(after.outbox()).isEqualTo(before.outbox());
    }

    @Test
    @DisplayName("the summary tells the app to hide 'Pay from wallet' while the flag is off")
    void summaryReportsFlagOff() throws Exception {
        var s = new CreditWalletSupport(mvc, json, jdbc);
        var line = s.creditLine("200000");
        var json = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/outlets/" + line.buyer().outletId() + "/credit/summary")
                        .header("Authorization", "Bearer " + line.buyer().token()))
                .andReturn().getResponse().getContentAsString();
        assertThat(this.json.readTree(json).at("/data/walletRepayEnabled").isMissingNode()).isFalse();
        assertThat(this.json.readTree(json).at("/data/walletRepayEnabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the flag defaults to off outside the tests that turn it on")
    void flagDefaultsOff() {
        assertThat(flag).isFalse();
    }
}
