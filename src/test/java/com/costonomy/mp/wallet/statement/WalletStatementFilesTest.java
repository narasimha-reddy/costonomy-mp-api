package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.wallet.domain.WalletDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two file formats and the arithmetic a statement must pass before it exists (D-108). */
class WalletStatementFilesTest {

    private static final StatementPeriod PERIOD = new StatementPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    private static WalletStatement.Line line(String at, WalletDirection dir, String amount, String balance,
                                             String description, String reference, String note) {
        return new WalletStatement.Line(Instant.parse(at), description, reference, dir,
                new BigDecimal(amount), new BigDecimal(balance), note);
    }

    private static WalletStatement statement(String outlet, List<WalletStatement.Line> lines) {
        return new WalletStatement(outlet, PERIOD, Instant.parse("2026-10-01T05:00:00Z"),
                new BigDecimal("100.00"), new BigDecimal("150.50"), new BigDecimal("80.50"), new BigDecimal("30.00"),
                lines);
    }

    private static String csv(WalletStatement s) {
        return new String(WalletStatementCsv.render(s), StandardCharsets.UTF_8);
    }

    // ── CSV ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV: CRLF lines, every line the same width, summary then table, IST times")
    void csvShape() {
        var out = csv(statement("Paradise", List.of(
                line("2026-09-30T19:00:00Z", WalletDirection.CREDIT, "80.50", "180.50", "Money added", "", ""))));

        var lines = out.split("\r\n", -1);
        assertThat(out).endsWith("\r\n").doesNotContain("\n\n");
        assertThat(lines[0]).startsWith("Costonomy wallet statement");
        assertThat(out).contains("Outlet,Paradise,,,,,,,,\r\n")
                .contains("Period (IST),2026-09-01 to 2026-09-30")
                .contains("Opening balance (INR),100.00")
                .contains("Total added (INR),80.50")
                .contains("Total spent (INR),30.00")
                .contains("Closing balance (INR),150.50");
        // 30 Sep 19:00 UTC is 1 Oct 00:30 in India.
        assertThat(out).contains("2026-10-01 00:30:00,Money added,,Credit,80.50,180.50,,,,\r\n");
        // D-116: the bill's three columns come after the ledger's, which keep their places.
        assertThat(out).contains("Date and time (IST),Description,Reference,Direction,Amount (INR),"
                + "Balance after (INR),Note,Bill,Shop,Bill no.\r\n");
        for (String l : lines) {
            if (!l.isEmpty()) {
                assertThat(l.chars().filter(c -> c == ',').count()).describedAs(l).isEqualTo(9);
            }
        }
    }

    @Test
    @DisplayName("CSV: commas, quotes and line breaks are quoted the RFC 4180 way")
    void csvQuoting() {
        var out = csv(statement("Ravi, \"Best\" Biryani\nHouse", List.of(
                line("2026-09-10T05:00:00Z", WalletDirection.DEBIT, "30.00", "70.00", "Paid for an order",
                        "CO-1", "said \"hi\", left\r\nnext line"))));

        assertThat(out).contains("Outlet,\"Ravi, \"\"Best\"\" Biryani\nHouse\"");
        assertThat(out).contains("\"said \"\"hi\"\", left\r\nnext line\"");
    }

    @Test
    @DisplayName("CSV: text that would run as a formula is neutralised; numbers are left alone")
    void csvFormulaInjection() {
        var out = csv(statement("=cmd|' /C calc'!A0", List.of(
                line("2026-09-10T05:00:00Z", WalletDirection.CREDIT, "1.00", "101.00", "Money added", "@SUM(A1)", "+1-2"),
                line("2026-09-10T06:00:00Z", WalletDirection.CREDIT, "1.00", "102.00", "Money added", "", "-2+3"),
                line("2026-09-10T07:00:00Z", WalletDirection.CREDIT, "1.00", "103.00", "Money added", "", "=HYPERLINK(\"http://x\",\"y\")"),
                line("2026-09-10T08:00:00Z", WalletDirection.CREDIT, "1.00", "104.00", "Money added", "", "\tTAB"))));

        assertThat(out).contains("Outlet,'=cmd|' /C calc'!A0");
        assertThat(out).contains(",'@SUM(A1),Credit,1.00,101.00,'+1-2");
        assertThat(out).contains(",'-2+3");
        assertThat(out).contains("'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
        assertThat(out).contains(",'\tTAB");
        // An amount is a number, never prefixed.
        assertThat(out).contains("Credit,1.00,101.00").doesNotContain("'1.00").doesNotContain("'101.00");
        assertThat(WalletStatementCsv.text("safe = text")).isEqualTo("safe = text");
        assertThat(WalletStatementCsv.text("")).isEmpty();
        assertThat(WalletStatementCsv.text(null)).isEmpty();
    }

    @Test
    @DisplayName("CSV: an empty statement is still a statement")
    void csvEmpty() {
        var out = csv(new WalletStatement("Paradise", PERIOD, Instant.parse("2026-10-01T05:00:00Z"),
                new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        assertThat(out).contains("Opening balance (INR),5.00").contains("Closing balance (INR),5.00")
                .contains("Date and time (IST),Description");
    }

    // ── PDF ──────────────────────────────────────────────────────────────

    private static String pdfText(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    private static int pageCount(byte[] pdf) {
        var m = Pattern.compile("/Type /Page /Parent").matcher(pdfText(pdf));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("PDF: a valid file shape, rupees as Rs., summary on page one")
    void pdfShape() {
        byte[] pdf = WalletStatementPdf.render(statement("Paradise ₹ Cafe (Banjara)", List.of(
                line("2026-09-30T19:00:00Z", WalletDirection.CREDIT, "80.50", "180.50", "Money added", "CO-9",
                        ""))));
        String text = pdfText(pdf);

        assertThat(text).startsWith("%PDF-1.4");
        assertThat(text.stripTrailing()).endsWith("%%EOF");
        assertThat(text).contains("Costonomy wallet statement").contains("Rs. 100.00").contains("Rs. 150.50")
                .contains("Paradise Rs. Cafe \\(Banjara\\)").contains("01 Oct 2026 00:30").contains("Page 1 of 1");
        assertThat(text).doesNotContain("₹");
        assertThat(pageCount(pdf)).isEqualTo(1);
        // The cross-reference table points at real objects.
        int startxref = Integer.parseInt(text.substring(text.lastIndexOf("startxref") + 10).split("\n")[0].trim());
        assertThat(text.substring(startxref)).startsWith("xref");
        var offsets = Pattern.compile("(\\d{10}) 00000 n").matcher(text);
        int objectNumber = 1;
        while (offsets.find()) {
            assertThat(text.substring(Integer.parseInt(offsets.group(1)))).startsWith(objectNumber + " 0 obj");
            objectNumber++;
        }
        assertThat(objectNumber).isGreaterThan(5);
    }

    @Test
    @DisplayName("PDF: many rows run onto more pages, each repeating the table header, in order")
    void pdfPaginates() {
        var lines = new ArrayList<WalletStatement.Line>();
        BigDecimal balance = new BigDecimal("100.00");
        for (int i = 0; i < 300; i++) {
            balance = balance.add(BigDecimal.ONE);
            lines.add(line("2026-09-10T05:00:00Z", WalletDirection.CREDIT, "1.00", balance.toPlainString(),
                    "Money added", "", ""));
        }
        var s = new WalletStatement("Paradise", PERIOD, Instant.parse("2026-10-01T05:00:00Z"),
                new BigDecimal("100.00"), balance, new BigDecimal("300.00"), BigDecimal.ZERO, lines);

        byte[] pdf = WalletStatementPdf.render(s);
        String text = pdfText(pdf);

        int pages = pageCount(pdf);
        // A4 landscape since D-116: 25 rows on page one, 32 on the others.
        assertThat(pages).isEqualTo(10);
        assertThat(text).contains("/MediaBox [0 0 842 595]");
        assertThat(text).contains("/Count " + pages);
        assertThat(count(text, "(Date and time) Tj")).isEqualTo(pages);
        assertThat(text).contains("Page 1 of " + pages).contains("Page " + pages + " of " + pages);
        // Every row is drawn exactly once.
        assertThat(count(text, "(Money added) Tj")).isEqualTo(300);
        assertThat(text).contains("(400.00) Tj");
        // Continuation pages say whose statement it is.
        assertThat(count(text, "Costonomy wallet statement - Paradise")).isEqualTo(pages - 1);
    }

    @Test
    @DisplayName("D-116: the Bill, Shop and Bill no. columns, in the CSV (as text, neutralised) and the PDF")
    void billColumns() {
        var at = Instant.parse("2026-09-10T05:00:00Z");
        var rows = List.of(
                new WalletStatement.Line(at, "Paid a shop (QuickScan)", "", WalletDirection.DEBIT,
                        new BigDecimal("10.00"), new BigDecimal("90.00"), "", "Reviewed", "Kosta Delights, Sea Food",
                        "INV-7"),
                new WalletStatement.Line(at, "Paid for an order", "CO-1", WalletDirection.DEBIT,
                        new BigDecimal("20.00"), new BigDecimal("70.00"), "", "Pending", "", ""),
                new WalletStatement.Line(at, "Paid a shop (QuickScan)", "", WalletDirection.DEBIT,
                        new BigDecimal("5.00"), new BigDecimal("65.00"), "", "No bill needed", "", ""),
                new WalletStatement.Line(at, "Paid a shop (QuickScan)", "", WalletDirection.DEBIT,
                        new BigDecimal("1.00"), new BigDecimal("64.00"), "", "Added", "=evil()", "+1"),
                line("2026-09-10T06:00:00Z", WalletDirection.CREDIT, "1.00", "65.00", "Money added", "", ""));
        var out = csv(statement("Paradise", rows));
        assertThat(out).contains(",Debit,10.00,90.00,,Reviewed,\"Kosta Delights, Sea Food\",INV-7\r\n")
                .contains(",CO-1,Debit,20.00,70.00,,Pending,,\r\n")
                .contains(",Debit,5.00,65.00,,No bill needed,,\r\n")
                .contains(",Debit,1.00,64.00,,Added,'=evil(),'+1\r\n")
                .contains("Money added,,Credit,1.00,65.00,,,,\r\n");

        String pdf = pdfText(WalletStatementPdf.render(statement("Paradise", rows)));
        assertThat(pdf).contains("(Bill) Tj").contains("(Shop) Tj").contains("(Bill no.) Tj")
                .contains("(Reviewed) Tj").contains("(Kosta Delights, Sea Food) Tj").contains("(INV-7) Tj")
                .contains("(Pending) Tj").contains("(No bill needed) Tj");
    }

    @Test
    @DisplayName("PDF: text the standard fonts cannot draw is replaced, never left to corrupt the file")
    void pdfHostileText() {
        byte[] pdf = WalletStatementPdf.render(statement("A\\B) (C हिंदी 😀\u0001", List.of()));
        String text = pdfText(pdf);
        assertThat(text).contains("A\\\\B\\) \\(C ?").doesNotContain("\u0001");
        assertThat(pdf.length).isPositive();
        assertThat(text).contains("No entries in this period.");
    }

    @Test
    @DisplayName("PDF: rupees grouped the Indian way")
    void indianGrouping() {
        assertThat(WalletStatementPdf.indian(new BigDecimal("0"))).isEqualTo("0.00");
        assertThat(WalletStatementPdf.indian(new BigDecimal("999.5"))).isEqualTo("999.50");
        assertThat(WalletStatementPdf.indian(new BigDecimal("1000"))).isEqualTo("1,000.00");
        assertThat(WalletStatementPdf.indian(new BigDecimal("100000"))).isEqualTo("1,00,000.00");
        assertThat(WalletStatementPdf.indian(new BigDecimal("12345678.905"))).isEqualTo("1,23,45,678.91");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    // ── The arithmetic ───────────────────────────────────────────────────

    @Test
    @DisplayName("opening + added - spent = closing, or the statement is refused")
    void reconciliation() {
        // 100 + 80.50 - 30 = 150.50
        WalletStatementService.verify(1L, statement("Paradise", List.of()));

        var wrong = new WalletStatement("Paradise", PERIOD, Instant.now(), new BigDecimal("100.00"),
                new BigDecimal("150.51"), new BigDecimal("80.50"), new BigDecimal("30.00"), List.of());
        assertThatThrownBy(() -> WalletStatementService.verify(1L, wrong))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not reconcile");
        // Scale is not value: 150.5 is 150.50.
        WalletStatementService.verify(1L, new WalletStatement("P", PERIOD, Instant.now(), new BigDecimal("100"),
                new BigDecimal("150.5000"), new BigDecimal("80.5"), new BigDecimal("30.0000"), List.of()));
    }
}
