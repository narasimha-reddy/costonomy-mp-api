package com.costonomy.mp.credit.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * Builds a CSV file for the credit exports (D-172). UTF-8 and no byte order mark, rows ended by CRLF.
 *
 * <p>Text cells are made safe against spreadsheet formulas: a cell that starts with {@code = + - @} (or a tab or
 * carriage return) gets a single quote in front, so Excel and Sheets show it instead of running it. A restaurant's name
 * or a payment reference is typed by somebody else. Money cells are numbers written by us (plain decimals, two places,
 * a minus sign where the amount is negative) and are not touched: a quote in front of {@code -500.00} would break it.
 */
public final class CreditCsv {

    private final StringBuilder out = new StringBuilder();

    /** A row of text cells. */
    public CreditCsv text(String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(guard(cells[i])));
        }
        out.append("\r\n");
        return this;
    }

    /** A row from a mix: a {@link BigDecimal} is written as a plain number, anything else as guarded text. */
    public CreditCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object cell = cells[i];
            if (cell instanceof BigDecimal number) {
                out.append(number.setScale(2, RoundingMode.HALF_UP).toPlainString());
            } else if (cell instanceof Number number) {
                out.append(number);
            } else {
                out.append(quote(guard(cell == null ? "" : cell.toString())));
            }
        }
        out.append("\r\n");
        return this;
    }

    public CreditCsv blank() {
        out.append("\r\n");
        return this;
    }

    public byte[] bytes() {
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String guard(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }

    static String quote(String value) {
        boolean needs = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        return needs ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
