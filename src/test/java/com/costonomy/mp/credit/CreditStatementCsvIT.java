package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditCsvSupport.Csv;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement CSV and the store collections CSV (B13, D-146): the CSV is the JSON statement row by row, safe against
 * formulas, correctly quoted, named, audited, and shows each side only its own data.
 */
class CreditStatementCsvIT extends CreditClockedIT {

    private static final String PLAIN_DECIMAL = "-?\\d+\\.\\d{2}";

    /** A line with two invoices (₹6,500 and ₹3,000) and a ₹1,000 payment recorded by the supplier. */
    private record Fx(Line line, long first, long second) {
    }

    private Fx fx() throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "65", 100);
        long second = s.invoice(line, "30", 100);
        var paid = e.call("POST", line.seller().token(), "/api/v1/credit/invoices/" + first + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "1000.00", "method", "UPI", "reference", "UTR998877"));
        assertThat(paid.status()).describedAs(paid.body().toString()).isEqualTo(200);
        return new Fx(line, first, second);
    }

    private String statementPath(long agreement, String query) {
        return "/api/v1/credit/agreements/" + agreement + "/statement" + query;
    }

    private Csv csv(String token, long agreement, String query) throws Exception {
        return CreditCsvSupport.get(mvc, token, statementPath(agreement, ".csv" + query));
    }

    @Test
    @DisplayName("the CSV is the JSON statement: same opening and closing, same rows in the same order, plain two-place decimals, IST with offset")
    void csvEqualsJsonRowByRow() throws Exception {
        var f = fx();
        long agreement = f.line().agreementId();
        var json = e.call("GET", f.line().seller().token(), statementPath(agreement, ""), null, null);
        var csv = csv(f.line().seller().token(), agreement, "");

        assertThat(csv.status()).isEqualTo(200);
        assertThat(json.status()).isEqualTo(200);
        var data = json.data();
        assertThat(new BigDecimal(csv.row("Opening owed").get(1))).isEqualByComparingTo(data.get("openingOwed").decimalValue());
        assertThat(new BigDecimal(csv.row("Closing owed").get(1))).isEqualByComparingTo(data.get("closingOwed").decimalValue());
        assertThat(csv.row("Closing owed").get(1)).matches(PLAIN_DECIMAL);
        assertThat(csv.row("From").get(1)).isEqualTo(data.get("from").asText());
        assertThat(csv.row("To").get(1)).isEqualTo(data.get("to").asText());
        assertThat(csv.row("Restaurant").get(1)).isEqualTo("Paradise");
        assertThat(csv.row("Outlet").get(1)).isEqualTo("Banjara Hills");
        assertThat(csv.row("Supplier").get(1)).isEqualTo("ABC Foods");

        var table = csv.table("At (IST)");
        assertThat(table.get(0)).containsExactly("At (IST)", "Type", "Description", "Amount", "Owed after", "Order",
                "Invoice", "Paid by", "Method", "Reference");
        var lines = data.get("lines");
        assertThat(table.size() - 1).isEqualTo(lines.size()).isEqualTo(3);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var row = table.get(i + 1);
            assertThat(row).hasSize(10);
            assertThat(row.get(0)).endsWith("+05:30");
            assertThat(OffsetDateTime.parse(row.get(0)).toInstant())
                    .isEqualTo(Instant.parse(line.get("at").asText()).truncatedTo(ChronoUnit.SECONDS));
            assertThat(row.get(1)).isEqualTo(line.get("type").asText());
            assertThat(row.get(2)).isEqualTo(line.get("label").asText());
            assertThat(row.get(3)).matches(PLAIN_DECIMAL);
            assertThat(row.get(4)).matches(PLAIN_DECIMAL);
            assertThat(new BigDecimal(row.get(3))).isEqualByComparingTo(line.get("amount").decimalValue());
            assertThat(new BigDecimal(row.get(4))).isEqualByComparingTo(line.get("owedAfter").decimalValue());
            assertThat(row.get(5)).isEqualTo(line.path("orderNumber").asText(""));
            assertThat(row.get(6)).isEqualTo(line.path("invoiceNumber").asText(""));
            assertThat(row.get(7)).isEqualTo(line.path("source").asText(""));
            assertThat(row.get(8)).isEqualTo(line.path("method").asText(""));
            assertThat(row.get(9)).isEqualTo(line.path("reference").asText(""));
            sum = sum.add(new BigDecimal(row.get(3)));
        }
        assertThat(new BigDecimal(csv.row("Opening owed").get(1)).add(sum))
                .describedAs("opening + rows = closing, in the file itself")
                .isEqualByComparingTo(new BigDecimal(csv.row("Closing owed").get(1)));
        assertThat(table.stream().skip(1).map(r -> r.get(3))).anyMatch(a -> a.startsWith("-"));
    }

    @Test
    @DisplayName("same window as the JSON: a past window and an explicit window give the same opening, closing and rows")
    void sameWindowsAsTheJson() throws Exception {
        var f = fx();
        long agreement = f.line().agreementId();
        String token = f.line().seller().token();
        for (String query : List.of("?from=" + today().minusDays(30) + "&to=" + today().minusDays(10),
                "?from=" + today() + "&to=" + today(), "?from=" + today().minusDays(5))) {
            var json = e.call("GET", token, statementPath(agreement, query), null, null);
            var csv = csv(token, agreement, query);
            assertThat(csv.status()).describedAs(query).isEqualTo(200);
            assertThat(new BigDecimal(csv.row("Opening owed").get(1))).describedAs(query)
                    .isEqualByComparingTo(json.data().get("openingOwed").decimalValue());
            assertThat(new BigDecimal(csv.row("Closing owed").get(1))).describedAs(query)
                    .isEqualByComparingTo(json.data().get("closingOwed").decimalValue());
            assertThat(csv.table("At (IST)").size() - 1).describedAs(query).isEqualTo(json.data().get("lines").size());
        }
        // The same validation as the JSON.
        String bad = "?from=" + today() + "&to=" + today().minusDays(1);
        assertThat(e.call("GET", token, statementPath(agreement, bad), null, null).status()).isEqualTo(400);
        assertThat(csv(token, agreement, bad).status()).isEqualTo(400);
        String tooLong = "?from=" + today().minusDays(400) + "&to=" + today();
        assertThat(csv(token, agreement, tooLong).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("file shape: text/csv UTF-8, attachment named statement-<outlet>-<from>-<to>.csv, no byte order mark, CRLF rows")
    void fileShape() throws Exception {
        var f = fx();
        String from = today().minusDays(10).toString();
        String to = today().toString();
        var csv = csv(f.line().seller().token(), f.line().agreementId(), "?from=" + from + "&to=" + to);

        assertThat(csv.contentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(csv.disposition()).contains("attachment").contains("statement-banjara-hills-" + from + "-" + to + ".csv");
        assertThat(csv.bytes()[0]).describedAs("no BOM").isNotEqualTo((byte) 0xEF);
        assertThat(csv.text()).startsWith("Credit statement,");
        assertThat(csv.text()).contains("not a GST tax invoice").contains("\r\n");
        assertThat(csv.text()).describedAs("names only, no phone numbers").doesNotContainPattern("\\d{10}");
    }

    @Test
    @DisplayName("either side may export; another restaurant, another supplier and a stranger get 404 and no audit row")
    void accessAndAudit() throws Exception {
        var f = fx();
        long agreement = f.line().agreementId();

        var supplier = csv(f.line().seller().token(), agreement, "");
        var restaurant = csv(f.line().buyer().token(), agreement, "");
        assertThat(supplier.status()).isEqualTo(200);
        assertThat(restaurant.status()).isEqualTo(200);
        assertThat(restaurant.table("At (IST)")).isEqualTo(supplier.table("At (IST)"));
        assertThat(exports("CREDIT_AGREEMENT", agreement)).isEqualTo(2);
        var row = jdbc.queryForMap("select actor_id, entity_type, new_state, reason from audit_log "
                + "where action = 'CREDIT_EXPORT' and entity_type = 'CREDIT_AGREEMENT' and entity_id = ? order by id limit 1", agreement);
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(e.userId(f.line().seller().token()));
        assertThat(row.get("entity_type")).isEqualTo("CREDIT_AGREEMENT");
        assertThat(row.get("new_state").toString()).isEqualTo("STATEMENT_CSV rows=3");
        assertThat(row.get("reason").toString()).contains(today().toString());

        for (String stranger : List.of(s.newBuyer().token(), s.newSeller().token(), s.api.loginFresh())) {
            assertThat(csv(stranger, agreement, "").status()).isEqualTo(404);
        }
        assertThat(exports("CREDIT_AGREEMENT", agreement)).describedAs("a refusal leaves no audit row").isEqualTo(2);
    }

    @Test
    @DisplayName("each line's export holds only that line: another line of the same supplier is not in it")
    void onlyThisLine() throws Exception {
        var f = fx();
        var another = s.creditLine(f.line().buyer(), "50000", Map.of());
        long anotherInvoice = s.invoice(another, "77", 10);
        var csv = csv(f.line().seller().token(), f.line().agreementId(), "");
        assertThat(csv.text()).doesNotContain(invoiceNumber(anotherInvoice));
        assertThat(csv(another.seller().token(), another.agreementId(), "").text()).contains(invoiceNumber(anotherInvoice))
                .doesNotContain(invoiceNumber(f.first()));
    }

    @Test
    @DisplayName("formula injection: cells starting with = + - @ get a leading quote; money stays a number; commas, quotes and newlines are quoted")
    void injectionAndQuoting() throws Exception {
        var f = fx();
        long agreement = f.line().agreementId();
        var evil = e.call("POST", f.line().seller().token(), "/api/v1/credit/invoices/" + f.second() + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "500.00", "method", "CASH",
                        "reference", "=HYPERLINK(\"http://evil\",\"x\")"));
        assertThat(evil.status()).describedAs(evil.body().toString()).isEqualTo(200);
        var plus = e.call("POST", f.line().seller().token(), "/api/v1/credit/invoices/" + f.second() + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "100.00", "method", "CASH", "reference", "+91 98765"));
        assertThat(plus.status()).isEqualTo(200);
        var minus = e.call("POST", f.line().seller().token(), "/api/v1/credit/invoices/" + f.second() + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "100.00", "method", "CASH", "reference", "-2+3"));
        assertThat(minus.status()).isEqualTo(200);
        var at = e.call("POST", f.line().seller().token(), "/api/v1/credit/invoices/" + f.second() + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "100.00", "method", "CASH", "reference", "@SUM(A1)"));
        assertThat(at.status()).isEqualTo(200);
        var comma = e.call("POST", f.line().seller().token(), "/api/v1/credit/invoices/" + f.second() + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "100.00", "method", "CASH",
                        "reference", "ref, with \"quotes\" and a comma"));
        assertThat(comma.status()).isEqualTo(200);
        jdbc.update("update outlet set name = ? where id = ?", "=cmd|' /C calc'!A0", f.line().buyer().outletId());
        jdbc.update("update restaurant set name = ? where id = (select restaurant_id from outlet where id = ?)",
                "Hotel \"Sara\", Banjara\nHills", f.line().buyer().outletId());

        var csv = csv(f.line().seller().token(), agreement, "");
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.row("Outlet").get(1)).isEqualTo("'=cmd|' /C calc'!A0");
        assertThat(csv.row("Restaurant").get(1)).describedAs("quoted newline, comma and quotes survive")
                .isEqualTo("Hotel \"Sara\", Banjara\nHills");
        var references = csv.table("At (IST)").stream().skip(1).map(r -> r.get(9)).toList();
        assertThat(references).contains("'=HYPERLINK(\"http://evil\",\"x\")", "'+91 98765", "'-2+3", "'@SUM(A1)",
                "ref, with \"quotes\" and a comma", "UTR998877");
        for (var row : csv.table("At (IST)").stream().skip(1).toList()) {
            assertThat(row.get(3)).describedAs("amounts are never quoted or altered").matches(PLAIN_DECIMAL);
            for (String cell : row) {
                assertThat(cell).describedAs("no cell may begin with a formula character").doesNotStartWith("=")
                        .doesNotStartWith("+").doesNotStartWith("@");
            }
        }
        // The file name is made from the outlet name and cannot carry the formula.
        assertThat(csv.disposition()).contains("statement-cmd-c-calc-a0-");
    }

    // ── store collections ────────────────────────────────────────────────

    private Csv collections(String token, long store, String query) throws Exception {
        return CreditCsvSupport.get(mvc, token, "/api/v1/supplier-stores/" + store + "/credit/collections.csv" + query);
    }

    @Test
    @DisplayName("collections.csv: every payment of this store, newest first, names only, plain decimals, equal to the JSON feed; another store's payments are not in it")
    void collectionsCsv() throws Exception {
        var f = fx();
        var other = fx();   // another store with its own payment
        long store = f.line().seller().storeId();
        var csv = collections(f.line().seller().token(), store, "");
        var feed = e.call("GET", f.line().seller().token(), "/api/v1/supplier-stores/" + store
                + "/credit/payments?size=100", null, null);

        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.contentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(csv.disposition()).contains("collections-store-" + store + "-start-" + today() + ".csv");
        var rows = csv.rows();
        assertThat(rows.get(0)).containsExactly("Payment id", "Paid at (IST)", "Restaurant", "Outlet", "Invoice",
                "Amount", "Source", "Method", "Reference");
        assertThat(rows.size() - 1).isEqualTo(feed.data().get("items").size()).isEqualTo(1);
        var row = rows.get(1);
        var item = feed.data().at("/items/0");
        assertThat(row.get(0)).isEqualTo(item.get("id").asText());
        assertThat(OffsetDateTime.parse(row.get(1)).toInstant())
                .isEqualTo(Instant.parse(item.get("paidAt").asText()).truncatedTo(ChronoUnit.SECONDS));
        assertThat(row.get(1)).endsWith("+05:30");
        assertThat(row.get(2)).isEqualTo("Paradise");
        assertThat(row.get(3)).isEqualTo("Banjara Hills");
        assertThat(row.get(4)).isEqualTo(invoiceNumber(f.first()));
        assertThat(row.get(5)).isEqualTo("1000.00");
        assertThat(row.get(6)).isEqualTo("SUPPLIER_RECORDED");
        assertThat(row.get(7)).isEqualTo("UPI");
        assertThat(row.get(8)).isEqualTo("UTR998877");
        assertThat(csv.text()).doesNotContain(invoiceNumber(other.first())).doesNotContainPattern("\\d{10}");

        // Filters: a window with nothing in it, and a source with nothing in it.
        assertThat(collections(f.line().seller().token(), store, "?from=" + today().minusDays(30) + "&to="
                + today().minusDays(2)).rows()).hasSize(1);
        assertThat(collections(f.line().seller().token(), store, "?source=WALLET").rows()).hasSize(1);
        assertThat(collections(f.line().seller().token(), store, "?source=SUPPLIER_RECORDED").rows()).hasSize(2);

        // Audit: who and what range.
        assertThat(exports("SUPPLIER_STORE", store)).isEqualTo(4);
        var audit = jdbc.queryForMap("select actor_id, entity_type, new_state, reason from audit_log "
                + "where action = 'CREDIT_EXPORT' and entity_type = 'SUPPLIER_STORE' and entity_id = ? order by id limit 1", store);
        assertThat(((Number) audit.get("actor_id")).longValue()).isEqualTo(e.userId(f.line().seller().token()));
        assertThat(audit.get("entity_type")).isEqualTo("SUPPLIER_STORE");
        assertThat(audit.get("new_state")).isEqualTo("COLLECTIONS_CSV rows=1");
        assertThat(audit.get("reason").toString()).startsWith("Collections ");
    }

    @Test
    @DisplayName("collections.csv: the restaurant, another supplier and a stranger get 404 and no audit row")
    void collectionsAccess() throws Exception {
        var f = fx();
        long store = f.line().seller().storeId();
        for (String who : List.of(f.line().buyer().token(), s.newSeller().token(), s.api.loginFresh())) {
            assertThat(collections(who, store, "").status()).isEqualTo(404);
        }
        assertThat(exports("SUPPLIER_STORE", store)).isZero();
        String finance = l.staff(f.line(), "SUP_FINANCE_STAFF");
        assertThat(collections(finance, store, "").status()).isEqualTo(200);
        assertThat(exports("SUPPLIER_STORE", store)).isEqualTo(1);
    }

    @Test
    @DisplayName("a restaurant name that starts with a formula character is quoted in collections.csv too")
    void collectionsInjection() throws Exception {
        var f = fx();
        jdbc.update("update restaurant set name = '=1+1' where id = (select restaurant_id from outlet where id = ?)",
                f.line().buyer().outletId());
        var csv = collections(f.line().seller().token(), f.line().seller().storeId(), "");
        assertThat(csv.rows().get(1).get(2)).isEqualTo("'=1+1");
    }
}
