package com.costonomy.mp.procurement;

import com.costonomy.mp.procurement.domain.Pricing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line arithmetic. The one place a total is computed, so the one place it can be
 * wrong.
 */
class PricingTest {

    @Test
    @DisplayName("20 kg at ₹410 with 5% GST")
    void worksThroughARealLine() {
        var itemValue = Pricing.lineItemValue(new BigDecimal("410"), new BigDecimal("20"));
        var gst = Pricing.lineGst(itemValue, new BigDecimal("5"));

        assertThat(itemValue).isEqualByComparingTo("8200.00");
        assertThat(gst).isEqualByComparingTo("410.00");
        assertThat(Pricing.lineTotal(itemValue, gst)).isEqualByComparingTo("8610.00");
    }

    @Test
    @DisplayName("GST is computed on the rounded line value, not an unrounded intermediate")
    void gstFollowsTheRoundedLineValue() {
        // ₹410.555 × 3 = ₹1231.665, which rounds to ₹1231.67 on the invoice. GST
        // must be 5% of what is printed, or the invoice does not reconcile when a
        // human checks it with a calculator.
        var itemValue = Pricing.lineItemValue(new BigDecimal("410.555"), new BigDecimal("3"));
        assertThat(itemValue).isEqualByComparingTo("1231.67");

        var gst = Pricing.lineGst(itemValue, new BigDecimal("5"));
        assertThat(gst).isEqualByComparingTo("61.58");
    }

    @Test
    @DisplayName("every money result carries two decimal places")
    void moneyIsAlwaysPaise() {
        // The columns hold DECIMAL(19,4), but a figure that reaches a person is
        // rounded to paise. Four decimals in a total produce invoices that do not
        // add up.
        assertThat(Pricing.lineItemValue(new BigDecimal("410"), BigDecimal.ONE).scale()).isEqualTo(2);
        assertThat(Pricing.lineGst(new BigDecimal("100.00"), new BigDecimal("18")).scale()).isEqualTo(2);
        assertThat(Pricing.money(new BigDecimal("410.12345")).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("fractional quantities work")
    void handlesFractionalQuantities() {
        var itemValue = Pricing.lineItemValue(new BigDecimal("120"), new BigDecimal("2.5"));
        assertThat(itemValue).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("zero GST is zero, not a rounding artefact")
    void zeroGst() {
        assertThat(Pricing.lineGst(new BigDecimal("8200.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a price comparison ignores scale")
    void differsIgnoresScale() {
        // 410.00 and 410.0000 are the same price. Treating them as a change would
        // make every revalidation demand confirmation of a price that never moved,
        // which teaches restaurants to click through the confirmation that exists
        // to protect them.
        assertThat(Pricing.differs(new BigDecimal("410.00"), new BigDecimal("410.0000"))).isFalse();
        assertThat(Pricing.differs(new BigDecimal("410.00"), new BigDecimal("410.01"))).isTrue();
    }

    @Test
    @DisplayName("null comparisons do not throw")
    void differsHandlesNull() {
        assertThat(Pricing.differs(null, null)).isFalse();
        assertThat(Pricing.differs(new BigDecimal("410"), null)).isTrue();
    }
}
