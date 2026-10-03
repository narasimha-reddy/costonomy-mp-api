package com.costonomy.mp.wallet;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.invoice.CostAppStub;
import com.costonomy.mp.wallet.invoice.service.InvoiceReadingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.costonomy.mp.wallet.InvoiceTestSupport.*;
import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The cost app refuses our sign-in (a wrong or changed password), D-114: bills stay READING with a plain sentence,
 * no attempt is used up (it is not the bill's fault), the cost app is not asked again for 30 seconds however often
 * the retry job runs, and the lookups answer a plain 503.
 */
@AutoConfigureMockMvc
class WalletInvoiceReaderLoginIT extends AbstractIntegrationTest {

    private static final String AT = "2026-09-10 10:00:00.000000";
    private static final CostAppStub STUB = new CostAppStub();
    private static final Path STORE = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("mp-invoices-login-it");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void reader(DynamicPropertyRegistry registry) {
        STUB.requireAuth(true);
        STUB.password("the-cost-app-now-wants-another-one");
        registry.add("costonomy.mp.invoices.reader.provider", () -> "HTTP");
        registry.add("costonomy.mp.invoices.reader.base-url", STUB::baseUrl);
        registry.add("costonomy.mp.invoices.reader.username", () -> CostAppStub.USERNAME);
        registry.add("costonomy.mp.invoices.reader.password", () -> CostAppStub.PASSWORD);
        registry.add("costonomy.mp.invoices.reader.outlet", () -> "5");
        registry.add("costonomy.mp.invoices.reader.user-id", () -> "6");
        registry.add("costonomy.mp.invoices.storage.local.directory", STORE::toString);
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InvoiceReadingService reading;

    @Test
    @DisplayName("wrong password: the bill stays READING with a plain sentence, no attempt used, one sign-in per 30 s")
    void wrongPassword() throws Exception {
        var t = new WalletTopUpSupport(mvc, json, jdbc);
        Buyer buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit(AT, TOP_UP, "9000", "start");
        seed.debit(AT, ORDER_PAYMENT, "2820", "Order paid from wallet");
        long entry = seed.lastId;

        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        await().atMost(15, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(current(buyer, entry).get("error").isNull()).isFalse());

        for (int i = 0; i < 4; i++) {
            reading.retryDue(10);
        }

        var d = current(buyer, entry);
        assertThat(d.get("status").asText()).isEqualTo("READING");
        assertThat(d.get("error").asText()).isEqualTo("We could not read this bill yet. We will try again.");
        assertThat(d.get("attempts").asInt()).isZero();
        assertThat(d.get("reading").isNull()).isTrue();
        assertThat(STUB.logins()).isEqualTo(1);
        assertThat(STUB.seen("/item-purchase/invoice/extract")).isEmpty();

        // The pickers say so plainly, without asking the cost app again.
        var lookup = call(mvc, json, "GET", buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/invoice-lookups/suppliers");
        assertThat(lookup.status()).isEqualTo(503);
        assertThat(lookup.body().at("/error/message").asText())
                .isEqualTo("The cost app's lists are not available right now. You can still type a name.");
        assertThat(STUB.logins()).isEqualTo(1);

        String everything = d.toString() + lookup.body();
        assertThat(everything).doesNotContain(CostAppStub.PASSWORD).doesNotContain("INVOICE_READER");
    }

    private JsonNode current(Buyer buyer, long entry) throws Exception {
        return call(mvc, json, "GET", buyer.token(), path(buyer.outletId(), entry)).data();
    }
}
