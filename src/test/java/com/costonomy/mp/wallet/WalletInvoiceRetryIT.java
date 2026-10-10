package com.costonomy.mp.wallet;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReadException;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReader;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import com.costonomy.mp.wallet.invoice.service.InvoiceJobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.costonomy.mp.wallet.InvoiceTestSupport.*;
import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A reader that is down, slow or finds no bill (D-113): the bill stays READING and is tried again by the job,
 * and after five attempts it is UNREADABLE, with its pages still in place.
 */
@AutoConfigureMockMvc
class WalletInvoiceRetryIT extends AbstractIntegrationTest {

    private static final String AT = "2026-09-10 10:00:00.000000";

    @MockBean private InvoiceReader reader;
    @Autowired private InvoiceJobs jobs;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private WalletTopUpSupport t;
    private Buyer buyer;
    private long entry;

    @BeforeEach
    void setUp() throws Exception {
        // Bills other tests left READING would be retried by the job and take this test's scripted answers.
        jdbc.update("update wallet_entry_invoice set status = 'UNREADABLE' where status = 'READING'");
        t = new WalletTopUpSupport(mvc, json, jdbc);
        buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit(AT, TOP_UP, "9000", "start");
        seed.debit(AT, ORDER_PAYMENT, "2820", "Order paid from wallet");
        entry = seed.lastId;
    }

    private JsonNode current() throws Exception {
        return call(mvc, json, "GET", buyer.token(), path(buyer.outletId(), entry)).data();
    }

    private void awaitAttempts(int attempts) {
        await().atMost(10, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(current().get("attempts").asInt()).isEqualTo(attempts));
    }

    private static InvoiceReading reading() {
        return new InvoiceReading("Shop", null, "9", "1/1/26", null, "INR", List.of(), null, null, null,
                new BigDecimal("2820"));
    }

    @Test
    @DisplayName("reader down: the upload still succeeds, the bill stays READING with its attempts, and is UNREADABLE after 5")
    void readerDown() throws Exception {
        when(reader.read(any(), any())).thenThrow(new InvoiceReadException("The invoice reader did not answer in time.", null));

        var created = upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().get("status").asText()).isEqualTo("READING");

        awaitAttempts(1);
        var first = current();
        assertThat(first.get("status").asText()).isEqualTo("READING");
        assertThat(first.get("error").asText()).isNotBlank();
        assertThat(first.get("reading").isNull()).isTrue();
        assertThat(first.at("/check/matches").isNull()).isTrue();

        for (int attempt = 2; attempt <= 4; attempt++) {
            jobs.retry();
            var now = current();
            assertThat(now.get("attempts").asInt()).isEqualTo(attempt);
            assertThat(now.get("status").asText()).isEqualTo("READING");
        }
        jobs.retry();

        var done = current();
        assertThat(done.get("status").asText()).isEqualTo("UNREADABLE");
        assertThat(done.get("attempts").asInt()).isEqualTo(5);
        assertThat(done.get("error").asText()).isEqualTo("We could not read this bill. You can still view the photo.");
        assertThat(done.get("pageCount").asInt()).isEqualTo(1);
        assertThat(done.at("/pages/0/url").asText()).contains("/invoice-files/");
        // The page is still on disk and still served.
        String url = done.at("/pages/0/url").asText();
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(url.substring(url.indexOf("/invoice-files/")))).andReturn().getResponse().getStatus()).isEqualTo(200);

        // An UNREADABLE bill is not tried again.
        jobs.retry();
        assertThat(current().get("attempts").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("a reading that fails at first and works on a retry ends READ")
    void recovers() throws Exception {
        when(reader.read(any(), any()))
                .thenThrow(new InvoiceReadException("The invoice reader could not be reached.", null))
                .thenReturn(InvoiceReader.ReadResult.of(reading()));

        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        awaitAttempts(1);
        jobs.retry();

        var d = current();
        assertThat(d.get("status").asText()).isEqualTo("READ");
        assertThat(d.get("attempts").asInt()).isEqualTo(2);
        assertThat(d.get("error").isNull()).isTrue();
        assertThat(d.at("/reading/vendorName").asText()).isEqualTo("Shop");
        assertThat(d.at("/check/matches").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a reader that answers but finds no bill counts as a failed attempt, not a reading")
    void noBillIsNotRead() throws Exception {
        when(reader.read(any(), any())).thenReturn(InvoiceReader.ReadResult.noBill("The reader found no bill."));

        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        awaitAttempts(1);
        var d = current();
        assertThat(d.get("status").asText()).isEqualTo("READING");
        assertThat(d.get("reading").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select vendor_name from wallet_entry_invoice where outlet_id = ?", String.class, buyer.outletId())).isNull();
    }

    @Test
    @DisplayName("removing a bill while it is being read leaves nothing behind and no error")
    void removedWhileReading() throws Exception {
        when(reader.read(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return InvoiceReader.ReadResult.of(reading());
        });
        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        assertThat(call(mvc, json, "DELETE", buyer.token(), path(buyer.outletId(), entry)).status()).isEqualTo(204);
        Thread.sleep(700);
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isZero();
    }

    // ── D-115 ────────────────────────────────────────────────────────────

    private int column(String name) {
        return jdbc.queryForObject("select " + name + " from wallet_entry_invoice where outlet_id = ?", Integer.class,
                buyer.outletId());
    }

    /** The next try made due now (the back-off is tested on its own), then the job runs. */
    private void dueAndRetry() {
        jdbc.update("update wallet_entry_invoice set next_try_at = now(6) - interval 1 second "
                + "where outlet_id = ? and next_try_at is not null", buyer.outletId());
        jobs.retry();
    }

    @Test
    @DisplayName("D-115 (H1): currency 'Indian Rupees (INR)' and a total of 10^15: the bill is READ with INR and no total, after one call")
    void oversizedValuesAreRead() throws Exception {
        when(reader.read(any(), any())).thenReturn(InvoiceReader.ReadResult.of(new InvoiceReading("Shop", null, "9",
                "1/1/26", null, "Indian Rupees (INR)", List.of(), new BigDecimal("2820"), null, null,
                new BigDecimal("1000000000000000"))));

        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        awaitAttempts(1);
        var d = current();
        assertThat(d.get("status").asText()).isEqualTo("READ");
        assertThat(d.at("/reading/currency").asText()).isEqualTo("INR");
        assertThat(d.at("/reading/total").isNull()).isTrue();
        assertThat(d.at("/reading/subtotal").decimalValue()).isEqualByComparingTo("2820");
        assertThat(jdbc.queryForObject("select currency from wallet_entry_invoice where outlet_id = ?", String.class,
                buyer.outletId())).isEqualTo("INR");
        assertThat(column("extract_calls")).isEqualTo(1);
        jobs.retry();
        verify(reader, times(1)).read(any(), any());
    }

    @Test
    @DisplayName("D-115 (H1): when the reading can never be written, each failure still counts: UNREADABLE with attempts 5 after exactly 5 calls, never stuck at 0")
    void unwritableReadingEndsUnreadable() throws Exception {
        when(reader.read(any(), any())).thenReturn(InvoiceReader.ReadResult.of(new InvoiceReading(
                "Write-Fails-Shop-9931", null, "9", "1/1/26", null, "INR", List.of(), null, null, null,
                new BigDecimal("2820"))));
        // The table refuses this one reading, as it refused a 19-character currency before the fix.
        jdbc.execute("alter table wallet_entry_invoice add constraint ck_test_write_fails "
                + "check (vendor_name is null or vendor_name <> 'Write-Fails-Shop-9931')");
        try {
            assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
            awaitAttempts(1);
            assertThat(current().get("status").asText()).isEqualTo("READING");
            for (int i = 0; i < 8; i++) {
                jobs.retry();
            }
            var d = current();
            assertThat(d.get("status").asText()).isEqualTo("UNREADABLE");
            assertThat(d.get("attempts").asInt()).isEqualTo(5);
            assertThat(d.get("error").asText()).isEqualTo("We could not read this bill. You can still view the photo.");
            assertThat(column("extract_calls")).isEqualTo(5);
            verify(reader, times(5)).read(any(), any());
        } finally {
            jdbc.execute("alter table wallet_entry_invoice drop check ck_test_write_fails");
        }
    }

    @Test
    @DisplayName("D-115 (M3): the cost app unavailable uses no attempt, waits 1 then 2 minutes, and the bill is read once it is back")
    void unavailableBacksOff() throws Exception {
        when(reader.read(any(), any()))
                .thenThrow(new com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException(
                        "The invoice reader refused the request for now (status 429).", false))
                .thenThrow(new com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException(
                        "The invoice reader answered with status 503.", true))
                .thenReturn(InvoiceReader.ReadResult.of(reading()));

        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        await().atMost(10, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(current().get("unavailableCount").asInt()).isEqualTo(1));
        var d = current();
        assertThat(d.get("status").asText()).isEqualTo("READING");
        assertThat(d.get("attempts").asInt()).isZero();
        assertThat(d.get("error").asText()).isEqualTo("We could not read this bill yet. We will try again.");
        assertThat(column("extract_calls")).isZero();                    // a 429 started no reading
        var wait = jdbc.queryForObject("select timestampdiff(SECOND, last_attempt_at, next_try_at) from "
                + "wallet_entry_invoice where outlet_id = ?", Long.class, buyer.outletId());
        assertThat(wait).isBetween(55L, 65L);
        jobs.retry();                                                     // not due yet: nothing is asked
        verify(reader, times(1)).read(any(), any());

        dueAndRetry();
        assertThat(current().get("unavailableCount").asInt()).isEqualTo(2);
        assertThat(column("extract_calls")).isEqualTo(1);                // a 503 may have started one: it counts
        wait = jdbc.queryForObject("select timestampdiff(SECOND, last_attempt_at, next_try_at) from "
                + "wallet_entry_invoice where outlet_id = ?", Long.class, buyer.outletId());
        assertThat(wait).isBetween(115L, 125L);

        dueAndRetry();
        d = current();
        assertThat(d.get("status").asText()).isEqualTo("READ");
        assertThat(d.get("attempts").asInt()).isEqualTo(1);
        assertThat(d.get("nextTryAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("D-115 (F): worst case, one bill causes at most max-attempts (5) extraction calls, whatever the cost app answers")
    void extractionCallsAreCapped() throws Exception {
        // A cost app that fails after starting the work, every time (a 5xx or a 408): no attempt is used, yet the
        // ceiling holds.
        when(reader.read(any(), any())).thenThrow(new com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException(
                "The invoice reader answered with status 500.", true));
        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(current().get("unavailableCount").asInt()).isEqualTo(1));
        for (int i = 0; i < 20; i++) {
            dueAndRetry();
        }
        var d = current();
        assertThat(d.get("status").asText()).isEqualTo("UNREADABLE");
        assertThat(d.get("attempts").asInt()).isZero();
        assertThat(column("extract_calls")).isEqualTo(5);
        verify(reader, times(5)).read(any(), any());
    }

    @Test
    @DisplayName("D-115 (F): refusals that start no reading (403, 429, sign-in) are not extraction calls, and stop after max-unavailable (48)")
    void refusalsAreCappedToo() throws Exception {
        when(reader.read(any(), any())).thenThrow(new com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException(
                "The invoice reader refused the request for now (status 403).", false));
        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(current().get("unavailableCount").asInt()).isEqualTo(1));
        for (int i = 0; i < 60; i++) {
            dueAndRetry();
        }
        var d = current();
        assertThat(d.get("status").asText()).isEqualTo("UNREADABLE");
        assertThat(d.get("attempts").asInt()).isZero();
        assertThat(d.get("unavailableCount").asInt()).isEqualTo(48);
        assertThat(column("extract_calls")).isZero();
        verify(reader, times(48)).read(any(), any());
    }
}
