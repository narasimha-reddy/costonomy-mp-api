package com.costonomy.mp.wallet;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monthly limit and the configurable limits, with limits small enough to
 * reach (D-107). The defaults are ₹1,00,000 in the wallet and ₹10,00,000 a
 * month; the monthly one cannot be reached with a balance capped below it unless
 * money is spent in between, which these tests do straight in SQL.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "costonomy.mp.wallet.max-balance=1000",
        "costonomy.mp.wallet.monthly-top-up-limit=1500",
        "costonomy.mp.wallet.min-top-up=25",
        "costonomy.mp.wallet.max-top-up=800"})
class WalletTopUpLimitsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider provider;
    @Autowired private com.costonomy.mp.wallet.service.WalletTopUpService topUpService;

    private WalletTopUpSupport t;

    @BeforeEach
    void setUp() {
        t = new WalletTopUpSupport(mvc, json, jdbc);
    }

    private long credit(Buyer buyer, String amount) throws Exception {
        var created = t.createOk(buyer, amount);
        long id = created.get("topUpId").asLong();
        var payment = provider.completeCheckout(created.get("razorpayOrderId").asText());
        var reply = t.confirm(buyer, id, payment.providerPaymentId());
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return id;
    }

    /** The restaurant spends its balance; the month's top-ups stay counted. */
    private void spendEverything(Buyer buyer) {
        jdbc.update("update wallet set balance = 0 where outlet_id = ?", buyer.outletId());
    }

    @Test
    @DisplayName("limits come from configuration and are what the wallet reports")
    void configuredLimits() throws Exception {
        var buyer = t.newBuyer();

        var limits = t.wallet(buyer).data().get("limits");

        assertThat(limits.get("maxBalance").decimalValue()).isEqualByComparingTo("1000");
        assertThat(limits.get("monthlyTopUpLimit").decimalValue()).isEqualByComparingTo("1500");
        assertThat(limits.get("minTopUp").decimalValue()).isEqualByComparingTo("25");
        assertThat(limits.get("maxTopUp").decimalValue()).isEqualByComparingTo("800");
        assertThat(t.create(buyer, "24.99", "a").status()).isEqualTo(400);
        assertThat(t.create(buyer, "800.01", "b").status()).isEqualTo(400);
        assertThat(t.create(buyer, "800.00", "c").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the monthly limit refuses a top-up at creation, naming what is left, and allows landing exactly on it")
    void monthlyLimitAtCreate() throws Exception {
        var buyer = t.newBuyer();
        credit(buyer, "800.00");
        spendEverything(buyer);
        credit(buyer, "600.00");
        spendEverything(buyer);
        // 1,400 added of 1,500.

        var refused = t.create(buyer, "101.00", UUID.randomUUID().toString());
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("WALLET_LIMIT_EXCEEDED");
        assertThat(refused.body().at("/error/message").asText()).contains("100");

        assertThat(t.wallet(buyer).data().at("/limits/remainingThisMonth").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(t.create(buyer, "100.00", UUID.randomUUID().toString()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("last month's top-ups do not count against this month")
    void lastMonthDoesNotCount() throws Exception {
        var buyer = t.newBuyer();
        long id = credit(buyer, "800.00");
        spendEverything(buyer);
        long second = credit(buyer, "600.00");
        spendEverything(buyer);
        var startOfMonth = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        t.creditedAt(startOfMonth, -60, id, second);

        assertThat(t.wallet(buyer).data().at("/limits/addedThisMonth").decimalValue()).isEqualByComparingTo("0");
        assertThat(t.create(buyer, "800.00", UUID.randomUUID().toString()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("one outlet's month does not count against another's")
    void monthIsPerOutlet() throws Exception {
        var a = t.newBuyer();
        var b = t.newBuyer();
        credit(a, "800.00");
        spendEverything(a);
        credit(a, "600.00");

        assertThat(t.wallet(b).data().at("/limits/addedThisMonth").decimalValue()).isEqualByComparingTo("0");
        assertThat(t.create(b, "800.00", UUID.randomUUID().toString()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the monthly limit is checked again when the money lands: over it, the payment is refunded, not credited")
    void monthlyLimitAtCreditTime() throws Exception {
        var buyer = t.newBuyer();
        // Both created while the month is empty, so both pass the creation check.
        var a = t.createOk(buyer, "800.00");
        var b = t.createOk(buyer, "800.00");
        long idA = a.get("topUpId").asLong();
        long idB = b.get("topUpId").asLong();
        var payA = provider.completeCheckout(a.get("razorpayOrderId").asText());
        var payB = provider.completeCheckout(b.get("razorpayOrderId").asText());
        assertThat(t.confirm(buyer, idA, payA.providerPaymentId()).status()).isEqualTo(200);
        spendEverything(buyer);

        var reply = t.confirm(buyer, idB, payB.providerPaymentId());

        // 800 + 800 > 1,500. The balance is empty, so only the month's limit is what stops it.
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("WALLET_LIMIT_EXCEEDED");
        assertThat(t.dbStatus(idB)).isEqualTo("REFUNDED");
        assertThat(t.ledgerRows(idB)).isZero();
        assertThat(t.balance(buyer)).isEqualByComparingTo("0");
    }
}
