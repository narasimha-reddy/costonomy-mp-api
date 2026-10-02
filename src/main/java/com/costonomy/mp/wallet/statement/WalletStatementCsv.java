package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.wallet.domain.WalletDirection;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A statement as CSV a spreadsheet will open safely (D-108).
 *
 * <p><b>RFC 4180.</b> CRLF line ends, fields with a comma, quote or line break in double
 * quotes, quotes doubled. Every line has the same number of fields, so the summary lines
 * above the table are padded rather than ragged.
 *
 * <p><b>Formula injection.</b> Excel, Sheets and LibreOffice run a cell that begins with
 * {@code = + - @} (and, for some, a tab or carriage return) as a formula. Text in this
 * file comes from people — an outlet's name, a ledger note — so every <em>text</em> cell
 * that starts that way is prefixed with a single quote, which the spreadsheet shows as
 * nothing and treats as "this is text". Amounts are written as plain numbers and are not
 * text: they are never negative here (direction is its own column), and quoting a number
 * would make it a string in the spreadsheet.
 */
public final class WalletStatementCsv {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(StatementPeriod.ZONE);
    private static final int COLUMNS = 7;

    private WalletStatementCsv() {
    }

    public static byte[] render(WalletStatement s) {
        var out = new StringBuilder();
        line(out, text("Costonomy wallet statement"));
        line(out, text("Outlet"), text(s.outletName()));
        line(out, text("Period (IST)"), text(s.period().from() + " to " + s.period().to()));
        line(out, text("Generated (IST)"), text(STAMP.format(s.generatedAt())));
        line(out, text("Opening balance (INR)"), number(s.openingBalance()));
        line(out, text("Total added (INR)"), number(s.totalAdded()));
        line(out, text("Total spent (INR)"), number(s.totalSpent()));
        line(out, text("Closing balance (INR)"), number(s.closingBalance()));
        line(out);
        line(out, text("Date and time (IST)"), text("Description"), text("Reference"), text("Direction"),
                text("Amount (INR)"), text("Balance after (INR)"), text("Note"));
        for (var row : s.lines()) {
            line(out, text(STAMP.format(row.at())), text(row.description()), text(row.reference()),
                    text(row.direction() == WalletDirection.CREDIT ? "Credit" : "Debit"),
                    number(row.amount()), number(row.balanceAfter()), text(row.note()));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A text cell, neutralised against being run as a formula, then quoted if it needs it. */
    static String text(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty()) {
            char first = v.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                v = "'" + v;
            }
        }
        return quote(v);
    }

    private static String number(java.math.BigDecimal value) {
        return Rupees.of(value);
    }

    private static String quote(String v) {
        boolean needs = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0
                || v.indexOf('\r') >= 0 || (!v.isEmpty() && (v.startsWith(" ") || v.endsWith(" ")));
        return needs ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static void line(StringBuilder out, String... cells) {
        var all = new ArrayList<>(List.of(cells));
        while (all.size() < COLUMNS) {
            all.add("");
        }
        out.append(String.join(",", all)).append("\r\n");
    }
}
