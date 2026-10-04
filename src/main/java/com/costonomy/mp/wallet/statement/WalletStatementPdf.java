package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.wallet.domain.WalletDirection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A statement as a PDF, written by hand (D-108).
 *
 * <p><b>Why not a library.</b> No PDF library (openpdf, pdfbox, iText) is in the local Maven
 * repository, the build runs offline, and adding a dependency that cannot be resolved would
 * break every build but the author's. A statement is plain text in a table, which PDF can do
 * with a page tree, one built-in font and a text stream, so this writes exactly that and
 * nothing else: A4 landscape (D-116: room for the Bill, Shop and Bill no. columns), Helvetica and Helvetica-Bold (standard fonts, nothing to embed), the
 * table header repeated on every page, and "Page n of m".
 *
 * <p><b>The rupee sign.</b> The standard fonts have no glyph for it, so amounts are shown
 * as "Rs." Any other character the fonts cannot draw becomes "?" rather than corrupting
 * the file. Text is written in WinAnsi, which covers every accented letter and the middle
 * dot the ledger wording uses.
 *
 * <p>Column widths are estimated from Helvetica's real digit and punctuation widths, so
 * numbers are exactly right-aligned; long descriptions are cut with "..." using a
 * deliberately generous width for letters, so text never runs into the next column.
 */
public final class WalletStatementPdf {

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH).withZone(StatementPeriod.ZONE);
    private static final DateTimeFormatter GENERATED =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'IST'", Locale.ENGLISH).withZone(StatementPeriod.ZONE);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private static final double PAGE_W = 842;
    private static final double PAGE_H = 595;
    private static final double MARGIN = 40;
    private static final double ROW_H = 14;
    private static final double FONT = 8;

    // Column left edges; the two amount columns are right-aligned to their right edges.
    private static final double X_DATE = MARGIN;
    private static final double X_DESC = 112;
    private static final double X_REF = 245;
    private static final double X_DIR = 318;
    private static final double R_AMOUNT = 420;
    private static final double R_BALANCE = 500;
    // D-116: the bill, after the ledger's columns.
    private static final double X_BILL = 512;
    private static final double X_SHOP = 580;
    private static final double X_BILL_NO = 712;
    private static final double R_PAGE = PAGE_W - MARGIN;

    private WalletStatementPdf() {
    }

    /** One page's drawing commands. */
    private static final class Page {
        final StringBuilder stream = new StringBuilder();

        void text(boolean bold, double size, double x, double y, String value) {
            stream.append("BT /").append(bold ? "F2" : "F1").append(' ').append(fmt(size)).append(" Tf ")
                    .append(fmt(x)).append(' ').append(fmt(y)).append(" Td (").append(escape(value)).append(") Tj ET\n");
        }

        void rightText(boolean bold, double size, double rightX, double y, String value) {
            text(bold, size, rightX - width(value, size), y, value);
        }

        void rule(double y) {
            stream.append("0.6 w ").append(fmt(MARGIN)).append(' ').append(fmt(y)).append(" m ")
                    .append(fmt(PAGE_W - MARGIN)).append(' ').append(fmt(y)).append(" l S\n");
        }
    }

    public static byte[] render(WalletStatement s) {
        var pages = new ArrayList<Page>();
        var rows = s.lines();
        int next = 0;

        do {
            var page = new Page();
            pages.add(page);
            double y = PAGE_H - MARGIN;
            if (pages.size() == 1) {
                y = firstPageHeader(page, s, y);
            } else {
                page.text(true, 9, MARGIN, y, "Costonomy wallet statement - " + s.outletName());
                page.text(false, 8, MARGIN, y - 12,
                        s.period().from().format(DAY) + " to " + s.period().to().format(DAY));
                y -= 34;
            }
            y = tableHeader(page, y);

            if (rows.isEmpty()) {
                page.text(false, 9, MARGIN, y - 4, "No entries in this period.");
            }
            while (next < rows.size() && y - ROW_H >= MARGIN + 24) {
                var row = rows.get(next++);
                y -= ROW_H;
                page.text(false, FONT, X_DATE, y, STAMP.format(row.at()));
                page.text(false, FONT, X_DESC, y, fit(row.description(), X_REF - X_DESC - 6));
                page.text(false, FONT, X_REF, y, fit(row.reference(), X_DIR - X_REF - 6));
                page.text(false, FONT, X_DIR, y, row.direction() == WalletDirection.CREDIT ? "Credit" : "Debit");
                page.rightText(false, FONT, R_AMOUNT, y, plain(row.amount()));
                page.rightText(false, FONT, R_BALANCE, y, plain(row.balanceAfter()));
                page.text(false, FONT, X_BILL, y, fit(row.bill(), X_SHOP - X_BILL - 6));
                page.text(false, FONT, X_SHOP, y, fit(row.shop(), X_BILL_NO - X_SHOP - 6));
                page.text(false, FONT, X_BILL_NO, y, fit(row.billNumber(), R_PAGE - X_BILL_NO));
            }
        } while (next < rows.size());

        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).rightText(false, 8, R_PAGE, MARGIN - 12,
                    "Page %d of %d".formatted(i + 1, pages.size()));
            pages.get(i).text(false, 7, MARGIN, MARGIN - 12, "Amounts in Indian rupees (Rs.). Times are IST.");
        }
        return assemble(pages);
    }

    private static double firstPageHeader(Page page, WalletStatement s, double top) {
        double y = top;
        page.text(true, 16, MARGIN, y, "Costonomy wallet statement");
        y -= 22;
        page.text(false, 10, MARGIN, y, "Outlet: " + s.outletName());
        y -= 14;
        page.text(false, 10, MARGIN, y,
                "Period: " + s.period().from().format(DAY) + " to " + s.period().to().format(DAY) + " (IST)");
        y -= 14;
        page.text(false, 8, MARGIN, y, "Generated " + GENERATED.format(s.generatedAt()));
        y -= 20;

        String[][] summary = {
                {"Opening balance", money(s.openingBalance())},
                {"Total added", money(s.totalAdded())},
                {"Total spent", money(s.totalSpent())},
                {"Closing balance", money(s.closingBalance())}};
        for (var line : summary) {
            page.text(false, 10, MARGIN, y, line[0]);
            page.rightText(true, 10, MARGIN + 220, y, line[1]);
            y -= 14;
        }
        return y - 10;
    }

    private static double tableHeader(Page page, double y) {
        page.text(true, FONT, X_DATE, y, "Date and time");
        page.text(true, FONT, X_DESC, y, "Description");
        page.text(true, FONT, X_REF, y, "Reference");
        page.text(true, FONT, X_DIR, y, "Direction");
        page.rightText(true, FONT, R_AMOUNT, y, "Amount (Rs.)");
        page.rightText(true, FONT, R_BALANCE, y, "Balance (Rs.)");
        page.text(true, FONT, X_BILL, y, "Bill");
        page.text(true, FONT, X_SHOP, y, "Shop");
        page.text(true, FONT, X_BILL_NO, y, "Bill no.");
        page.rule(y - 4);
        return y - 4;
    }

    // ── Text: what the standard fonts can draw ───────────────────────────

    /** "Rs. 1,00,000.00", grouped the Indian way. */
    static String money(BigDecimal amount) {
        return "Rs. " + indian(amount);
    }

    static String plain(BigDecimal amount) {
        return indian(amount);
    }

    static String indian(BigDecimal amount) {
        String digits = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        boolean negative = digits.startsWith("-");
        if (negative) {
            digits = digits.substring(1);
        }
        int dot = digits.indexOf('.');
        String whole = digits.substring(0, dot);
        String fraction = digits.substring(dot);
        var grouped = new StringBuilder();
        int n = whole.length();
        if (n <= 3) {
            grouped.append(whole);
        } else {
            String head = whole.substring(0, n - 3);
            String tail = whole.substring(n - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(tail);
        }
        return (negative ? "-" : "") + grouped + fraction;
    }

    /**
     * Text as it will be drawn: rupee sign spelled out, anything WinAnsi cannot hold turned
     * into "?", control characters dropped. Returned as a string of single-byte characters.
     */
    static String drawable(String value) {
        String v = (value == null ? "" : value).replace("₹", "Rs.");
        var sb = new StringBuilder();
        CharsetEncoder encoder = WIN_ANSI.newEncoder()
                .onUnmappableCharacter(CodingErrorAction.REPLACE).onMalformedInput(CodingErrorAction.REPLACE)
                .replaceWith(new byte[]{'?'});
        for (int i = 0; i < v.length(); ) {
            int cp = v.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7f) {
                sb.append(' ');
                continue;
            }
            String one = new String(Character.toChars(cp));
            try {
                var bytes = encoder.encode(java.nio.CharBuffer.wrap(one));
                sb.append((char) (bytes.get() & 0xff));
            } catch (java.nio.charset.CharacterCodingException ex) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    static String escape(String value) {
        var sb = new StringBuilder();
        for (char c : drawable(value).toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Helvetica advance widths in 1/1000 em, exact for digits and common punctuation, generous for letters. */
    static double width(String value, double size) {
        double units = 0;
        for (char c : drawable(value).toCharArray()) {
            units += switch (c) {
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> 556;
                case '.', ',', ' ', ':', ';', '\'', 'i', 'j', 'l', '!', '|' -> 278;
                case '-', '(', ')', 'f', 't', 'r', 'I' -> 333;
                case 'm', 'M', 'W', 'w' -> 833;
                default -> Character.isUpperCase(c) ? 722 : 556;
            };
        }
        return units * size / 1000.0;
    }

    private static String fit(String value, double maxWidth) {
        String v = drawable(value);
        if (width(v, FONT) <= maxWidth) {
            return v;
        }
        String cut = v;
        while (!cut.isEmpty() && width(cut + "...", FONT) > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    private static String fmt(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ── The file itself ──────────────────────────────────────────────────

    private static byte[] assemble(List<Page> pages) {
        var objects = new ArrayList<byte[]>();
        int firstPage = 5;
        var kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(firstPage + 2 * i).append(" 0 R ");
        }
        objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(ascii("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
        for (int i = 0; i < pages.size(); i++) {
            int content = firstPage + 2 * i + 1;
            objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] "
                    + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + content + " 0 R >>"));
            byte[] body = pages.get(i).stream.toString().getBytes(StandardCharsets.ISO_8859_1);
            var stream = new ByteArrayOutputStream();
            write(stream, ascii("<< /Length " + body.length + " >>\nstream\n"));
            write(stream, body);
            write(stream, ascii("endstream"));
            objects.add(stream.toByteArray());
        }

        var out = new ByteArrayOutputStream();
        write(out, ascii("%PDF-1.4\n"));
        write(out, new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, ascii((i + 1) + " 0 obj\n"));
            write(out, objects.get(i));
            write(out, ascii("\nendobj\n"));
        }
        int xref = out.size();
        var table = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append("%010d 00000 n \n".formatted(offset));
        }
        table.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        write(out, ascii(table.toString()));
        return out.toByteArray();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        try {
            out.write(bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
