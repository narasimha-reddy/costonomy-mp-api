package com.costonomy.mp.wallet.invoice;

import com.costonomy.mp.wallet.invoice.service.InvoiceCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceCheckTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("no bill total yet: matches and difference are null")
    void noTotal() {
        var check = InvoiceCheck.of(d("2820.0000"), null);
        assertThat(check.paid()).isEqualByComparingTo("2820");
        assertThat(check.billTotal()).isNull();
        assertThat(check.matches()).isNull();
        assertThat(check.difference()).isNull();
    }

    @Test
    @DisplayName("a difference of half a rupee or less matches; more does not")
    void tolerance() {
        assertThat(InvoiceCheck.of(d("2820"), d("2820")).matches()).isTrue();
        assertThat(InvoiceCheck.of(d("2820"), d("2820.50")).matches()).isTrue();
        assertThat(InvoiceCheck.of(d("2820"), d("2819.50")).matches()).isTrue();
        assertThat(InvoiceCheck.of(d("2820"), d("2820.51")).matches()).isFalse();
        assertThat(InvoiceCheck.of(d("2820"), d("2819.49")).matches()).isFalse();
    }

    @Test
    @DisplayName("difference is the bill total minus what was paid")
    void difference() {
        assertThat(InvoiceCheck.of(d("2820"), d("3000")).difference()).isEqualByComparingTo("180");
        assertThat(InvoiceCheck.of(d("2820"), d("2800")).difference()).isEqualByComparingTo("-20");
    }
}
