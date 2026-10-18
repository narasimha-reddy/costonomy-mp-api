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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

/**
 * Online payment on, the wallet-repayment switch left off (D-199, open question 2): UPI and card work, the wallet
 * method needs both switches and is refused with nothing moved.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=false"})
class CreditPayWalletOffIT extends AbstractIntegrationTest {

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

    @Test
    @DisplayName("the wallet method is 403 FORBIDDEN 'Paying from your wallet isn't available yet.' and nothing moves")
    void walletMethodRefused() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var before = p.snapshot(setup);

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(403);
        assertThat(reply.code()).isEqualTo("FORBIDDEN");
        assertThat(reply.body().at("/error/message").asText()).isEqualTo("Paying from your wallet isn't available yet.");
        assertThat(p.snapshot(setup)).isEqualTo(before);
    }

    @Test
    @DisplayName("the picker and the quote leave the wallet out; UPI and card still work")
    void onlineStillWorks() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");

        var payable = p.payable(setup.buyer());
        var quote = p.quote(setup.buyer(), p.allItems(setup));

        assertThat(payable.data().get("onlinePayEnabled").asBoolean()).isTrue();
        assertThat(payable.data().get("walletPayEnabled").asBoolean()).isFalse();
        assertThat(payable.data().get("methods")).hasSize(2);
        assertThat(payable.data().at("/methods/0").asText()).isEqualTo("UPI");
        assertThat(payable.data().at("/methods/1").asText()).isEqualTo("CARD");
        assertThat(quote.data().at("/methods/2/method").asText()).isEqualTo("WALLET");
        assertThat(quote.data().at("/methods/2/available").asBoolean()).isFalse();
        assertThat(quote.data().at("/methods/2/unavailableReason").asText()).isEqualTo("WALLET_OFF");
        assertThat(p.create(setup.buyer(), p.allItems(setup), "UPI").status()).isEqualTo(201);
    }
}
