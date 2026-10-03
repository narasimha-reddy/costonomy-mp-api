package com.costonomy.mp.wallet;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import com.costonomy.mp.wallet.statement.WalletStatementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wallet statements (D-108): the ledger turned into a file that must add up.
 *
 * <p>Rows are placed relative to today in Asia/Kolkata, at midday so no test is a coin
 * flip near midnight, except the ones that are about midnight, which use fixed dates.
 */
@AutoConfigureMockMvc
class WalletStatementIT extends AbstractIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter UTC_LITERAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneId.of("UTC"));

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private WalletTopUpSupport t;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLog;

    @BeforeEach
    void setUp() {
        t = new WalletTopUpSupport(mvc, json, jdbc);
        serviceLog = (Logger) LoggerFactory.getLogger(WalletStatementService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLog.detachAppender(logs);
    }

    private static LocalDate today() {
        return LocalDate.now(IST);
    }

    /** Midday IST on the day {@code days} before today, as the UTC literal the seed writes. */
    private static String daysAgo(int days) {
        return UTC_LITERAL.format(today().minusDays(days).atTime(12, 0).atZone(IST).toInstant());
    }

    private MockHttpServletResponse get(Buyer buyer, String query) throws Exception {
        return getFor(buyer.token(), buyer.outletId(), query);
    }

    private MockHttpServletResponse getFor(String token, long outletId, String query) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/outlets/" + outletId + "/wallet/statement?" + query)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private String csv(Buyer buyer, String query) throws Exception {
        var response = get(buyer, query + "&format=CSV");
        assertThat(response.getStatus()).describedAs(body(response)).isEqualTo(200);
        return body(response);
    }

    private static BigDecimal summary(String csv, String label) {
        var m = Pattern.compile("^" + Pattern.quote(label) + ",([0-9.]+),", Pattern.MULTILINE).matcher(csv);
        assertThat(m.find()).describedAs(label + " in\n" + csv).isTrue();
        return new BigDecimal(m.group(1));
    }

    private static int dataRows(String csv) {
        int header = csv.indexOf("Date and time (IST)");
        var after = csv.substring(header).split("\r\n");
        int n = 0;
        for (int i = 1; i < after.length; i++) {
            if (!after[i].isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** Opening + added - spent = closing, read from the file like an accountant would. */
    private static void assertReconciles(String csv, String opening, String added, String spent, String closing, int rows) {
        var o = summary(csv, "Opening balance (INR)");
        var a = summary(csv, "Total added (INR)");
        var s = summary(csv, "Total spent (INR)");
        var c = summary(csv, "Closing balance (INR)");
        assertThat(o).isEqualByComparingTo(opening);
        assertThat(a).isEqualByComparingTo(added);
        assertThat(s).isEqualByComparingTo(spent);
        assertThat(c).isEqualByComparingTo(closing);
        assertThat(o.add(a).subtract(s)).isEqualByComparingTo(c);
        assertThat(dataRows(csv)).isEqualTo(rows);
    }

    @Nested
    @DisplayName("the figures")
    class Figures {

        private Buyer buyer;

        private void seedYear() throws Exception {
            buyer = t.newBuyer();
            new LedgerSeed(t, jdbc, buyer)
                    .credit(daysAgo(100), TOP_UP, "1000.00", "a")
                    .debit(daysAgo(40), ORDER_PAYMENT, "300.00", "b")
                    .credit(daysAgo(20), ORDER_REFUND, "50.50", "c")
                    .debit(daysAgo(3), QUICKSCAN_PAYMENT, "10.25", "d")
                    .sync();
        }

        @Test
        @DisplayName("every range reconciles: opening + added - spent = closing")
        void ranges() throws Exception {
            seedYear();

            assertReconciles(csv(buyer, "range=LAST_30"), "700.00", "50.50", "10.25", "740.25", 2);
            assertReconciles(csv(buyer, "range=LAST_90"), "1000.00", "50.50", "310.25", "740.25", 3);
            assertReconciles(csv(buyer, "range=LAST_180"), "0", "1050.50", "310.25", "740.25", 4);
            assertReconciles(csv(buyer, "range=LAST_365"), "0", "1050.50", "310.25", "740.25", 4);
            assertReconciles(csv(buyer, "range=CUSTOM&from=" + today().minusDays(50) + "&to=" + today().minusDays(10)),
                    "1000.00", "50.50", "300.00", "750.50", 2);
            // A single day is a period too.
            assertReconciles(csv(buyer, "range=CUSTOM&from=" + today().minusDays(3) + "&to=" + today().minusDays(3)),
                    "750.50", "0", "10.25", "740.25", 1);
        }

        @Test
        @DisplayName("rows are oldest first, in India's time, in the restaurant's words")
        void rowsAndWording() throws Exception {
            seedYear();

            var lines = csv(buyer, "range=LAST_180").split("\r\n");
            int header = 0;
            while (!lines[header].startsWith("Date and time")) {
                header++;
            }
            assertThat(lines[header + 1]).contains(",Money added,,Credit,1000.00,1000.00,a")
                    .startsWith(today().minusDays(100) + " 12:00:00");
            assertThat(lines[header + 2]).contains(",Paid for an order,,Debit,300.00,700.00,b");
            assertThat(lines[header + 3]).contains(",Order cancelled · money back,,Credit,50.50,750.50,c");
            assertThat(lines[header + 4]).contains(",Paid a shop (QuickScan),,Debit,10.25,740.25,d");
        }

        @Test
        @DisplayName("an empty period is opening = closing with no rows; a period before any row is zeros")
        void emptyPeriods() throws Exception {
            buyer = t.newBuyer();
            new LedgerSeed(t, jdbc, buyer).credit(daysAgo(100), TOP_UP, "1000.00", "only").sync();

            // Nothing in the last 30 days, but the wallet holds 1000 all the same.
            assertReconciles(csv(buyer, "range=LAST_30"), "1000.00", "0", "0", "1000.00", 0);
            // Before the wallet's first movement there was nothing.
            assertReconciles(csv(buyer, "range=CUSTOM&from=" + today().minusDays(400) + "&to=" + today().minusDays(300)),
                    "0", "0", "0", "0", 0);
        }

        @Test
        @DisplayName("a wallet that has never been used gives a statement of nothing, not an error")
        void neverUsed() throws Exception {
            var fresh = t.newBuyer();

            assertReconciles(csv(fresh, "range=LAST_30"), "0", "0", "0", "0", 0);
            assertThat(get(fresh, "range=LAST_30&format=PDF").getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("an order's number is the reference; other movements have none")
        void reference() throws Exception {
            buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            long order = seed.order("CO-2026-0042");
            seed.credit(daysAgo(5), TOP_UP, "500.00", "top up")
                    .add(daysAgo(4), "DEBIT", ORDER_PAYMENT, "120.00", "paid", order, null).sync();

            var out = csv(buyer, "range=LAST_30");

            assertThat(out).contains(",Paid for an order,CO-2026-0042,Debit,120.00,380.00,paid")
                    .contains(",Money added,,Credit,500.00,500.00,top up");
        }

        @Test
        @DisplayName("every kind has its wording")
        void wording() throws Exception {
            buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            int day = 20;
            for (WalletEntryKind kind : WalletEntryKind.values()) {
                seed.credit(daysAgo(day--), kind, "10.00", kind.name());
            }
            seed.sync();

            var out = csv(buyer, "range=LAST_30");

            assertThat(out).contains("Money added,,Credit,10.00,10.00,TOP_UP")
                    .contains("Paid for an order,,Credit,10.00,20.00,ORDER_PAYMENT")
                    .contains("Order cancelled · money back,,Credit,10.00,30.00,ORDER_REFUND")
                    .contains("Refund,,Credit,10.00,40.00,REFUND")
                    .contains("Sent back to your card or bank,,Credit,10.00,50.00,WITHDRAWAL")
                    .contains("Refund,,Credit,10.00,60.00,DISPUTE_REFUND")
                    .contains("Withdrawal returned to your wallet,,Credit,10.00,70.00,WITHDRAWAL_REVERSAL")
                    .contains("Paid a shop (QuickScan),,Credit,10.00,80.00,QUICKSCAN_PAYMENT")
                    .contains("QuickScan payment returned,,Credit,10.00,90.00,QUICKSCAN_RETURN");
        }

        @Test
        @DisplayName("a returned top-up is not on the statement: it never moved the balance")
        void returnedTopUpNotIncluded() throws Exception {
            buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit(daysAgo(5), TOP_UP, "500.00", "real").sync();
            seed.returnedTopUp(t.userId(buyer.token()), daysAgo(4), "999.00", "card", "1111");

            assertReconciles(csv(buyer, "range=LAST_30"), "0", "500.00", "0", "500.00", 1);
        }

        @Test
        @DisplayName("one info line per statement: outlet, period, rows; no row amounts")
        void logLine() throws Exception {
            seedYear();
            csv(buyer, "range=LAST_90");

            var infos = logs.list.stream().filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(infos).hasSize(1);
            assertThat(infos.get(0)).contains("outlet " + buyer.outletId()).contains("3 rows")
                    .doesNotContain("1000").doesNotContain("300.00").doesNotContain("50.50");
        }
    }

    @Nested
    @DisplayName("financial years")
    class FinancialYears {

        @Test
        @DisplayName("31 March 23:59:59.999999 IST is in the year that ends; 1 April 00:00 IST is in the next")
        void boundary() throws Exception {
            var buyer = t.newBuyer();
            new LedgerSeed(t, jdbc, buyer)
                    .credit("2025-03-31 18:29:59.999999", TOP_UP, "1", "last instant of FY 2024-25")
                    .credit("2025-03-31 18:30:00.000000", TOP_UP, "1000", "first instant of FY 2025-26")
                    .credit("2026-03-31 18:29:59.999999", TOP_UP, "100", "last instant of FY 2025-26")
                    .credit("2026-03-31 18:30:00.000000", TOP_UP, "10", "first instant of FY 2026-27")
                    .sync();

            assertReconciles(csv(buyer, "financialYear=2024-25"), "0", "1", "0", "1", 1);
            assertReconciles(csv(buyer, "financialYear=2025-26"), "1", "1100", "0", "1101", 2);
            // The year in progress runs to today, and closes on what the wallet holds.
            assertReconciles(csv(buyer, "financialYear=2026-27"), "1101", "10", "0", "1111", 1);

            var response = get(buyer, "financialYear=2025-26&format=CSV");
            assertThat(response.getHeader("Content-Disposition"))
                    .contains("costonomy-wallet-statement-2025-04-01-to-2026-03-31.csv");
            assertThat(body(response)).contains("Period (IST),2025-04-01 to 2026-03-31");
        }

        @Test
        @DisplayName("a year that has not started, and a malformed one, are refused")
        void refused() throws Exception {
            var buyer = t.newBuyer();
            int nextStart = today().getMonthValue() >= 4 ? today().getYear() + 1 : today().getYear();
            String future = "%d-%02d".formatted(nextStart, (nextStart + 1) % 100);

            assertThat(get(buyer, "financialYear=" + future + "&format=CSV").getStatus()).isEqualTo(400);
            assertThat(get(buyer, "financialYear=2025-27&format=CSV").getStatus()).isEqualTo(400);
            assertThat(get(buyer, "financialYear=2025&format=CSV").getStatus()).isEqualTo(400);
            assertThat(get(buyer, "financialYear=2025-26&range=LAST_30&format=CSV").getStatus()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("what may be asked for")
    class Validation {

        private Buyer buyer;

        @BeforeEach
        void buyer() throws Exception {
            buyer = t.newBuyer();
        }

        private void assertBadRequest(String query) throws Exception {
            var response = get(buyer, query);
            assertThat(response.getStatus()).describedAs(query).isEqualTo(400);
            assertThat(body(response)).contains("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("custom ranges: from after to, to in the future, missing ends, bad dates, over 366 days")
        void customRange() throws Exception {
            assertBadRequest("range=CUSTOM&from=" + today().minusDays(1) + "&to=" + today().minusDays(2) + "&format=CSV");
            assertBadRequest("range=CUSTOM&from=" + today() + "&to=" + today().plusDays(1) + "&format=CSV");
            assertBadRequest("range=CUSTOM&from=" + today().plusDays(1) + "&to=" + today().plusDays(2) + "&format=CSV");
            assertBadRequest("range=CUSTOM&from=" + today().minusDays(5) + "&format=CSV");
            assertBadRequest("range=CUSTOM&to=" + today().minusDays(5) + "&format=CSV");
            assertBadRequest("range=CUSTOM&from=yesterday&to=today&format=CSV");
            assertBadRequest("range=CUSTOM&from=" + today().minusDays(366) + "&to=" + today() + "&format=CSV");
            assertBadRequest("range=LAST_30&from=" + today().minusDays(5) + "&format=CSV");
            assertBadRequest("range=LAST_7&format=CSV");
            assertBadRequest("format=CSV");
            assertBadRequest("range=LAST_30");
            assertBadRequest("range=LAST_30&format=XLSX");
        }

        @Test
        @DisplayName("exactly 366 days and a period ending today are allowed")
        void limitsAllowed() throws Exception {
            assertThat(get(buyer, "range=CUSTOM&from=" + today().minusDays(365) + "&to=" + today() + "&format=CSV")
                    .getStatus()).isEqualTo(200);
            assertThat(get(buyer, "range=CUSTOM&from=" + today() + "&to=" + today() + "&format=csv")
                    .getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("the files")
    class Files {

        @Test
        @DisplayName("CSV: headers, RFC 4180 quoting and formula injection through a real ledger note")
        void csvFile() throws Exception {
            var buyer = t.newBuyer();
            new LedgerSeed(t, jdbc, buyer)
                    .credit(daysAgo(5), TOP_UP, "500.00", "=HYPERLINK(\"http://evil\",\"click\")")
                    .credit(daysAgo(4), TOP_UP, "1.00", "+1+1")
                    .credit(daysAgo(3), TOP_UP, "1.00", "@SUM(A1)")
                    .credit(daysAgo(2), TOP_UP, "1.00", "-2+3")
                    .credit(daysAgo(1), TOP_UP, "1.00", "hello, \"world\"")
                    .sync();

            var response = get(buyer, "range=LAST_30&format=CSV");
            String out = body(response);

            assertThat(response.getContentType()).startsWith("text/csv");
            assertThat(response.getHeader("Content-Disposition")).startsWith("attachment")
                    .contains("costonomy-wallet-statement-" + today().minusDays(29) + "-to-" + today() + ".csv");
            assertThat(out).contains(",\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\"")
                    .contains(",'+1+1").contains(",'@SUM(A1)").contains(",'-2+3")
                    .contains(",\"hello, \"\"world\"\"\"");
            // Nothing in the file is a live formula: no cell begins with one.
            for (String cell : out.split("[,\r\n]")) {
                assertThat(cell).doesNotStartWith("=").doesNotStartWith("@");
            }
        }

        @Test
        @DisplayName("PDF: content type, file name, %PDF, and pages beyond the first for many rows")
        void pdfFile() throws Exception {
            var buyer = t.newBuyer();
            new LedgerSeed(t, jdbc, buyer).bulkCredits(daysAgo(2), 150).sync();

            var response = get(buyer, "range=LAST_30&format=PDF");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).isEqualTo("application/pdf");
            assertThat(response.getHeader("Content-Disposition")).startsWith("attachment")
                    .contains("costonomy-wallet-statement-" + today().minusDays(29) + "-to-" + today() + ".pdf");
            byte[] bytes = response.getContentAsByteArray();
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(text).startsWith("%PDF-");
            assertThat(text.stripTrailing()).endsWith("%%EOF");
            var pages = Pattern.compile("/Type /Page /Parent").matcher(text).results().count();
            assertThat(pages).isGreaterThanOrEqualTo(3);
            assertThat(text).contains("Page 1 of " + pages).contains("Rs. 150.00");
        }

        @Test
        @DisplayName("the row cap: at the limit is fine, one more is a 422 that says to choose a shorter period")
        void rowCap() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer).bulkCredits(daysAgo(2), WalletStatementService.MAX_ROWS).sync();

            var ok = get(buyer, "range=LAST_30&format=CSV");
            assertThat(ok.getStatus()).isEqualTo(200);
            assertThat(dataRows(body(ok))).isEqualTo(WalletStatementService.MAX_ROWS);

            seed.credit(daysAgo(1), TOP_UP, "1.00", "one too many").sync();
            var tooMany = get(buyer, "range=LAST_30&format=CSV");
            assertThat(tooMany.getStatus()).isEqualTo(422);
            assertThat(body(tooMany)).contains("STATEMENT_TOO_LARGE").contains("shorter period");
            assertThat(get(buyer, "range=LAST_30&format=PDF").getStatus()).isEqualTo(422);
            // A shorter period that leaves the crowd out still works.
            assertThat(get(buyer, "range=CUSTOM&from=" + today().minusDays(1) + "&to=" + today() + "&format=CSV")
                    .getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("a statement that does not add up is not produced")
    class Inconsistent {

        @Test
        @DisplayName("a ledger row that does not follow from the one before: 500 and an ERROR, no file")
        void brokenChain() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer)
                    .credit(daysAgo(5), TOP_UP, "100.00", "a")
                    .credit(daysAgo(4), TOP_UP, "100.00", "b")
                    .credit(daysAgo(3), TOP_UP, "100.00", "c").sync();
            jdbc.update("update wallet_transaction set balance_after = balance_after + 1 where wallet_id = ? and reason = 'b'",
                    seed.walletId);

            var response = get(buyer, "range=LAST_30&format=CSV");

            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(body(response)).contains("INTERNAL_ERROR").doesNotContain("Opening balance");
            assertThat(get(buyer, "range=LAST_30&format=PDF").getStatus()).isEqualTo(500);
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("does not reconcile")
                    && e.getFormattedMessage().contains("outlet " + buyer.outletId()));
        }

        @Test
        @DisplayName("an opening balance the first row does not follow from is caught too")
        void brokenOpening() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer)
                    .credit(daysAgo(60), TOP_UP, "100.00", "old")
                    .credit(daysAgo(5), TOP_UP, "100.00", "recent").sync();
            jdbc.update("update wallet_transaction set balance_after = 150 where wallet_id = ? and reason = 'old'",
                    seed.walletId);

            assertThat(get(buyer, "range=LAST_30&format=CSV").getStatus()).isEqualTo(500);
        }

        @Test
        @DisplayName("a wallet whose balance the ledger does not explain: 500 and an ERROR")
        void balanceDisagrees() throws Exception {
            var buyer = t.newBuyer();
            new LedgerSeed(t, jdbc, buyer).credit(daysAgo(5), TOP_UP, "100.00", "a").sync();
            jdbc.update("update wallet set balance = 101 where outlet_id = ?", buyer.outletId());

            var response = get(buyer, "range=LAST_30&format=CSV");

            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("wallet holds 101"));
            // A period that ends before the last row is not compared with today's balance.
            assertThat(get(buyer, "range=CUSTOM&from=" + today().minusDays(30) + "&to=" + today().minusDays(6)
                    + "&format=CSV").getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("who may download")
    class Access {

        @Test
        @DisplayName("another outlet's customer gets a 404 and no file, no token gets a 401")
        void denied() throws Exception {
            var owner = t.newBuyer();
            var stranger = t.newBuyer();
            new LedgerSeed(t, jdbc, owner).credit(daysAgo(5), TOP_UP, "500.00", "owner money").sync();

            var csv = getFor(stranger.token(), owner.outletId(), "range=LAST_30&format=CSV");
            var pdf = getFor(stranger.token(), owner.outletId(), "range=LAST_30&format=PDF");
            var anonymous = mvc.perform(MockMvcRequestBuilders.get(
                            "/api/v1/outlets/" + owner.outletId() + "/wallet/statement?range=LAST_30&format=CSV"))
                    .andReturn().getResponse();

            assertThat(csv.getStatus()).isEqualTo(404);
            assertThat(pdf.getStatus()).isEqualTo(404);
            assertThat(body(csv)).doesNotContain("owner money").doesNotContain("500");
            assertThat(csv.getHeader("Content-Disposition")).isNull();
            assertThat(anonymous.getStatus()).isEqualTo(401);
            // Their own outlet's statement holds only their own money.
            assertReconciles(csv(stranger, "range=LAST_30"), "0", "0", "0", "0", 0);
            assertThat(csv(owner, "range=LAST_30")).contains("owner money");
        }
    }
}
