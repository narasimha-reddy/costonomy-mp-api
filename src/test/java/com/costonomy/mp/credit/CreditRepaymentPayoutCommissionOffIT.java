package com.costonomy.mp.credit;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** With costonomy.mp.credit.repayment-commission.enabled=false a payout carries no rate and no commission (D-156). */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.wallet-repay.enabled=true",
        "costonomy.mp.credit.repayment-commission.enabled=false"})
class CreditRepaymentPayoutCommissionOffIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("with the commission switch off the payout has a null rate and zero commission")
    void commissionPropertyOff() throws Exception {
        var s = new CreditWalletSupport(mvc, json, jdbc);
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");

        var reply = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", List.of(invoice)));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        var payout = jdbc.queryForMap("select * from credit_repayment_payout where credit_repayment_id = ?",
                reply.data().get("repaymentId").asLong());
        assertThat(payout.get("commission_rate_percent")).isNull();
        assertThat(payout.get("commission_configuration_id")).isNull();
        assertThat((BigDecimal) payout.get("commission_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) payout.get("amount")).isEqualByComparingTo("1000.00");
        assertThat(payout.get("status")).isEqualTo("PENDING");
    }
}
