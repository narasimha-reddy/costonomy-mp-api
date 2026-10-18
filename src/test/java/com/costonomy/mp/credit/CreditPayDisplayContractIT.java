package com.costonomy.mp.credit;

import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
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
 * What the rest of the app reads about online credit payments (D-199, WP-E): the summary's switch for hiding "Paid
 * direct" and showing "Pay dues", the supplier's payout list saying where the money came from, and the supplier's
 * payment feed filtered to online payments.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditPayDisplayContractIT extends AbstractIntegrationTest {

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

    private CreditWalletSupport.Reply get(String token, String path) throws Exception {
        return p.e.call("GET", token, path, null, null);
    }

    @Test
    @DisplayName("the summary says online pay is on, so the app hides 'Paid direct' and shows 'Pay dues'")
    void summaryOnlinePayEnabled() throws Exception {
        var setup = p.single("1000.00");

        var reply = get(setup.buyer().token(), "/api/v1/outlets/" + setup.buyer().outletId() + "/credit/summary");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("onlinePayEnabled").isMissingNode()).isFalse();
        assertThat(reply.data().get("onlinePayEnabled").asBoolean()).isTrue();
        assertThat(reply.data().get("walletRepayEnabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a payout made by an online payment says source ONLINE and the method paid with; a wallet one says WALLET and no method")
    void payoutSourceAndMethod() throws Exception {
        var buyer = p.s.newBuyer();
        var line = p.s.creditLine(buyer, "200000", Map.of());
        var online = new CreditPaySupport.Inv(line, p.invoiceOf(line, "2000.00"));
        var card = new CreditPaySupport.Inv(line, p.invoiceOf(line, "3000.00"));
        var wallet = new CreditPaySupport.Inv(line, p.invoiceOf(line, "500.00"));
        var setup = new CreditPaySupport.Setup(buyer, java.util.List.of(line), java.util.List.of(online, card, wallet));
        p.s.topUp(buyer, "1000.00");
        long upiAttempt = p.createOk(buyer, p.items(online), "UPI");
        assertThat(p.verify(buyer, upiAttempt, p.pay(upiAttempt, "upi")).status()).isEqualTo(200);
        long cardAttempt = p.createOk(buyer, p.items(card), "CARD");
        assertThat(p.verify(buyer, cardAttempt, p.pay(cardAttempt, "card")).status()).isEqualTo(200);
        assertThat(p.createOk(buyer, p.items(wallet), "WALLET")).isPositive();

        var reply = get(line.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/payouts");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var items = reply.data().get("items");
        assertThat(items).hasSize(3);
        int online1 = 0;
        int cards = 0;
        int wallets = 0;
        for (JsonNode item : items) {
            switch (item.get("source").asText()) {
                case "ONLINE" -> {
                    if ("UPI".equals(item.get("paymentMethod").asText())) {
                        online1++;
                        assertThat(item.get("grossAmount").decimalValue()).isEqualByComparingTo("2000.00");
                    } else {
                        assertThat(item.get("paymentMethod").asText()).isEqualTo("CARD");
                        cards++;
                        assertThat(item.get("grossAmount").decimalValue()).isEqualByComparingTo("3000.00");
                    }
                }
                case "WALLET" -> {
                    wallets++;
                    assertThat(item.get("paymentMethod").isNull()).isTrue();
                    assertThat(item.get("grossAmount").decimalValue()).isEqualByComparingTo("500.00");
                }
                default -> throw new AssertionError("unexpected source " + item);
            }
        }
        assertThat(online1).isEqualTo(1);
        assertThat(cards).isEqualTo(1);
        assertThat(wallets).isEqualTo(1);
        // The detail read carries them too.
        long payoutId = items.get(0).get("payoutId").asLong();
        var detail = get(line.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId()
                + "/credit/payouts/" + payoutId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().get("source").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("the supplier's payment feed can be filtered to online payments: source ONLINE, method UPI, reference the Razorpay payment id")
    void paymentFeedFilter() throws Exception {
        var setup = p.standard();
        p.s.topUp(setup.buyer(), "20000.00");
        var paid = p.createAndPay(setup.buyer(), p.items(setup.inv(0), setup.inv(1)));
        assertThat(p.verify(setup.buyer(), paid.attemptId(), paid.paymentId()).status()).isEqualTo(200);
        assertThat(p.createOk(setup.buyer(), p.items(setup.inv(2)), "WALLET")).isPositive();
        assertThat(p.supplierReceipt(setup.inv(2), "1.00").status()).describedAs("already paid, refused").isGreaterThanOrEqualTo(400);
        String base = "/api/v1/supplier-stores/" + setup.line(0).seller().storeId() + "/credit/payments";

        var online = get(setup.line(0).seller().token(), base + "?source=ONLINE");

        assertThat(online.status()).describedAs(online.body().toString()).isEqualTo(200);
        var rows = online.data().get("items");
        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            assertThat(row.get("source").asText()).isEqualTo("ONLINE");
            assertThat(row.get("method").asText()).isEqualTo("UPI");
            assertThat(row.get("reference").asText()).isEqualTo(paid.paymentId());
        }
        var wallet = get(setup.line(1).seller().token(), "/api/v1/supplier-stores/" + setup.line(1).seller().storeId()
                + "/credit/payments?source=ONLINE");
        assertThat(wallet.data().get("items")).describedAs("that supplier was paid from the wallet").isEmpty();
        var all = get(setup.line(0).seller().token(), base);
        assertThat(all.data().get("items")).hasSize(2);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
