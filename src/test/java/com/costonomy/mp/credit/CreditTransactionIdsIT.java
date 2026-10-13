package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Reply;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one transaction id both sides can quote for a credit payment made through Mandi: the wallet ledger entry id
 * (WALLET) or the Razorpay payment id (ONLINE). The restaurant reads {@code walletEntryId} on the pay result; the
 * supplier reads a single {@code transactionId} on the payout, the payment feeds and the invoice, plus the order
 * number on the invoice list. Additive and read-only.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"costonomy.mp.credit.online-pay.enabled=true",
        "costonomy.mp.credit.wallet-repay.enabled=true"})
class CreditTransactionIdsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext ctx;
    @Autowired private NotificationRelayAccess relay;
    @SpyBean private MockPaymentProvider provider;

    private CreditPaySupport p;

    @BeforeEach
    void setUp() {
        p = new CreditPaySupport(mvc, json, jdbc, provider, ctx, relay);
    }

    private JsonNode get(String token, String path) throws Exception {
        Reply r = p.e.call("GET", token, path, null, null);
        assertThat(r.status()).describedAs(path + " " + r.body()).isEqualTo(200);
        return r.data();
    }

    private JsonNode supplierPayouts(CreditWalletSupport.Line line) throws Exception {
        return get(line.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/payouts");
    }

    private JsonNode supplierFeed(CreditWalletSupport.Line line) throws Exception {
        return get(line.seller().token(), "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/payments");
    }

    private JsonNode lineFeed(CreditWalletSupport.Line line) throws Exception {
        return get(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/payments");
    }

    private JsonNode invoice(String token, long id) throws Exception {
        return get(token, "/api/v1/credit/invoices/" + id);
    }

    private long walletEntry(CreditPaySupport.Setup setup) {
        return jdbc.queryForObject("select t.id from wallet_transaction t join wallet w on w.id = t.wallet_id "
                + "where w.outlet_id = ? and t.kind = 'CREDIT_REPAYMENT'", Long.class, setup.buyer().outletId());
    }

    @Test
    @DisplayName("WALLET: the receipt, the supplier payout, both payment feeds and the invoice all carry the same wallet entry id")
    void walletPaymentCarriesTheEntryId() throws Exception {
        var setup = p.single("500.00");
        p.s.topUp(setup.buyer(), "5000.00");
        var line = setup.line(0);

        var reply = p.create(setup.buyer(), p.allItems(setup), "WALLET");
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        long entry = walletEntry(setup);
        long attemptId = reply.data().get("attemptId").asLong();

        assertThat(reply.data().get("walletEntryId").asLong()).isEqualTo(entry);
        assertThat(p.status(setup.buyer(), attemptId).data().get("walletEntryId").asLong()).isEqualTo(entry);

        var payout = supplierPayouts(line).at("/items/0");
        assertThat(payout.get("transactionId").asText()).isEqualTo(String.valueOf(entry));
        assertThat(payout.get("source").asText()).isEqualTo("WALLET");

        for (var feed : new JsonNode[]{supplierFeed(line), lineFeed(line)}) {
            var item = feed.at("/items/0");
            assertThat(item.get("transactionId").asText()).isEqualTo(String.valueOf(entry));
            assertThat(item.has("walletEntryId")).describedAs("supplier feed names no wallet").isFalse();
        }

        long invoiceId = setup.inv(0).id();
        for (String token : new String[]{line.seller().token(), setup.buyer().token()}) {
            var payment = invoice(token, invoiceId).at("/payments/0");
            assertThat(payment.get("transactionId").asText()).isEqualTo(String.valueOf(entry));
            assertThat(payment.get("walletEntryId").asLong()).isEqualTo(entry);
        }
    }

    @Test
    @DisplayName("ONLINE: the supplier payout, feeds and invoice carry the Razorpay payment id; the receipt has no wallet entry")
    void onlinePaymentCarriesThePaymentId() throws Exception {
        var setup = p.single("500.00");
        var line = setup.line(0);

        var paid = p.createAndPay(setup.buyer(), p.allItems(setup));
        var reply = p.verify(setup.buyer(), paid.attemptId(), paid.paymentId());
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("status").asText()).isEqualTo("PAID");
        assertThat(reply.data().get("razorpayPaymentId").asText()).isEqualTo(paid.paymentId());
        assertThat(reply.data().get("walletEntryId").isNull()).isTrue();

        var payout = supplierPayouts(line).at("/items/0");
        assertThat(payout.get("transactionId").asText()).isEqualTo(paid.paymentId());
        assertThat(payout.get("source").asText()).isEqualTo("ONLINE");
        assertThat(supplierFeed(line).at("/items/0/transactionId").asText()).isEqualTo(paid.paymentId());
        assertThat(lineFeed(line).at("/items/0/transactionId").asText()).isEqualTo(paid.paymentId());
        var payment = invoice(line.seller().token(), setup.inv(0).id()).at("/payments/0");
        assertThat(payment.get("transactionId").asText()).isEqualTo(paid.paymentId());
        assertThat(payment.get("walletEntryId").isNull()).isTrue();
    }

    @Test
    @DisplayName("not applicable: an unpaid online attempt has no wallet entry; a supplier-recorded payment has no transaction id")
    void nullWhereNotApplicable() throws Exception {
        var setup = p.single("500.00");
        var line = setup.line(0);

        var created = p.create(setup.buyer(), p.allItems(setup), "UPI");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().get("walletEntryId").isNull()).isTrue();

        assertThat(p.recordedPaymentStatus(setup, "500.00")).isEqualTo(200);
        var item = supplierFeed(line).at("/items/0");
        assertThat(item.get("source").asText()).isEqualTo("SUPPLIER_RECORDED");
        assertThat(item.get("transactionId").isNull()).isTrue();
        var payment = invoice(line.seller().token(), setup.inv(0).id()).at("/payments/0");
        assertThat(payment.get("transactionId").isNull()).isTrue();
    }

    @Test
    @DisplayName("the supplier's invoice list carries the order number the invoice detail returns")
    void invoiceListCarriesOrderNumber() throws Exception {
        var setup = p.single("500.00");
        var line = setup.line(0);

        var list = get(line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/invoices");
        var row = list.get(0);
        var detail = invoice(line.seller().token(), setup.inv(0).id());
        assertThat(detail.get("orderNumber").asText()).startsWith("MP-");
        assertThat(row.get("orderNumber").asText()).isEqualTo(detail.get("orderNumber").asText());
        assertThat(row.get("supplierOrderId").asLong()).isEqualTo(detail.get("supplierOrderId").asLong());
    }
}
