package com.costonomy.mp.wallet.invoice;

import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceReadingTest {

    @Test
    @DisplayName("every text is capped at 500 characters, blanks become null, and items stop at 100")
    void caps() {
        String long600 = "x".repeat(600);
        var items = new ArrayList<InvoiceReading.Item>();
        for (int i = 0; i < 150; i++) {
            items.add(new InvoiceReading.Item(long600, BigDecimal.ONE, "  ", BigDecimal.TEN, BigDecimal.TEN));
        }
        var capped = new InvoiceReading(long600, "   ", long600, long600, long600, long600, items, null, null, null,
                BigDecimal.ONE).capped();

        assertThat(capped.vendorName()).hasSize(500);
        assertThat(capped.vendorAddress()).isNull();
        assertThat(capped.invoiceNumber()).hasSize(500);
        assertThat(capped.invoiceDate()).hasSize(500);
        assertThat(capped.customerName()).hasSize(500);
        // D-115 (H1): the column holds 16 characters; 600 of text with no code in it is no currency at all.
        assertThat(capped.currency()).isNull();
        assertThat(capped.items()).hasSize(100);
        assertThat(capped.items().get(0).name()).hasSize(500);
        assertThat(capped.items().get(0).unit()).isNull();
    }

    @Test
    @DisplayName("a reading without items gets an empty list, never null")
    void noItems() {
        var capped = new InvoiceReading("A", null, null, null, null, null, null, null, null, null, null).capped();
        assertThat(capped.items()).isEqualTo(List.of());
    }

    @Test
    @DisplayName("D-115 (H1): currency is a 3-letter code when one is printed, else at most 16 characters; numbers of 10^14 or more are dropped, 4 decimals kept")
    void valuesFitTheColumns() {
        var r = new InvoiceReading("A", null, null, null, null, "Indian Rupees (INR)",
                List.of(new InvoiceReading.Item("x", new BigDecimal("1.23456"), null, new BigDecimal("1E15"),
                        new BigDecimal("99999999999999.9999"))),
                new BigDecimal("-1E14"), new BigDecimal("0.00005"), null, new BigDecimal("1000000000000000")).capped();
        assertThat(r.currency()).isEqualTo("INR");
        assertThat(r.total()).isNull();
        assertThat(r.subtotal()).isNull();
        assertThat(r.tax()).isEqualByComparingTo("0.0001");
        assertThat(r.items().get(0).quantity()).isEqualByComparingTo("1.2346");
        assertThat(r.items().get(0).unitPrice()).isNull();
        assertThat(r.items().get(0).total()).isEqualByComparingTo("99999999999999.9999");
        assertThat(cur("inr")).isEqualTo("INR");
        assertThat(cur("Rs.")).isEqualTo("Rs.");
        assertThat(cur("₹")).isEqualTo("₹");
        assertThat(cur("Rupees only, paid in cash")).isNull();
        assertThat(cur("   ")).isNull();
    }

    private static String cur(String text) {
        return new InvoiceReading(null, null, null, null, null, text, List.of(), null, null, null, null).capped()
                .currency();
    }
}
