package com.costonomy.mp.credit;

import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

/**
 * A refund due on the ONLINE channel is money Mandi holds (captured by Razorpay, owed to the supplier on payout), so a
 * supplier cannot mark it refunded, exactly as for a WALLET one (D-199, contract 3.6). An OFF_PLATFORM one can still
 * be cleared by the supplier.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditRefundDueOnlineIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;

    @BeforeEach
    void setUp() {
        reset(provider);
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
    }

    private long refundDue(CreditPaySupport.Setup setup, String channel) {
        var inv = setup.inv(0);
        jdbc.update("insert into credit_refund_due (credit_invoice_id, credit_agreement_id, outlet_id, supplier_store_id, "
                        + "amount, channel, status, note, idempotency_key) values (?, ?, ?, ?, 100.00, ?, 'OPEN', 'test', ?)",
                inv.id(), inv.line().agreementId(), inv.line().buyer().outletId(), inv.line().seller().storeId(),
                channel, "test:" + UUID.randomUUID());
        return jdbc.queryForObject("select max(id) from credit_refund_due", Long.class);
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from credit_refund_due where id = ?", String.class, id);
    }

    @Test
    @DisplayName("the supplier cannot mark an ONLINE refund due as refunded: 409 CREDIT_REFUND_OPS_ONLY, row stays OPEN, no audit")
    void supplierCannotClearOnline() throws Exception {
        var setup = p.single("1000.00");
        long id = refundDue(setup, "ONLINE");

        var reply = p.e.call("POST", setup.line(0).seller().token(), "/api/v1/credit/refunds-due/" + id + "/mark-refunded",
                null, Map.of());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("CREDIT_REFUND_OPS_ONLY");
        assertThat(status(id)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select refunded_by from credit_refund_due where id = ?", Long.class, id)).isNull();
        assertThat(p.n.audits("CREDIT_REFUND_MARKED", id)).isZero();
    }

    @Test
    @DisplayName("an OFF_PLATFORM refund due is still cleared by the supplier")
    void supplierStillClearsOffPlatform() throws Exception {
        var setup = p.single("1000.00");
        long id = refundDue(setup, "OFF_PLATFORM");

        var reply = p.e.call("POST", setup.line(0).seller().token(), "/api/v1/credit/refunds-due/" + id + "/mark-refunded",
                null, Map.of());

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("REFUNDED");
    }
}
