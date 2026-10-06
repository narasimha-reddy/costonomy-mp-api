package com.costonomy.mp.billing;

import com.costonomy.mp.billing.service.BillingDirectory.Line;
import com.costonomy.mp.billing.service.BillingDirectory.OrderFacts;
import com.costonomy.mp.billing.service.InvoiceCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceCalculatorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** Rs 100/kg, 5% GST. A line as the order stored it after weighing. */
    private static Line weighed(String accepted, String billable, String itemValue, String gst, String total) {
        return new Line(41L, "Fresh Milk", "0401", d(accepted), billable == null ? null : d(billable), "KG",
                d("100"), d("5"), d(itemValue), d(gst), d(total), null, null, null);
    }

    private static OrderFacts order(String fee, String accepted, String weightAdj, String doorstep, String finalPayable) {
        return new OrderFacts(7L, "READY_FOR_PICKUP", "SO-7", 1L, 2L, d(fee), d(accepted),
                weightAdj == null ? null : d(weightAdj), doorstep == null ? null : d(doorstep),
                finalPayable == null ? null : d(finalPayable));
    }

    @Test
    @DisplayName("10 kg accepted, 9.6 kg weighed: the invoice is the stored 1,008.00, with the tax split in two")
    void weighedLine() {
        var result = InvoiceCalculator.calculate(order("0", "1050", "42", null, "1008"),
                List.of(weighed("10", "9.6", "960", "48", "1008")), false);

        assertThat(result.total()).isEqualByComparingTo("1008.00");
        assertThat(result.taxable()).isEqualByComparingTo("960.00");
        assertThat(result.cgst()).isEqualByComparingTo("24.00");
        assertThat(result.sgst()).isEqualByComparingTo("24.00");
        assertThat(result.igst()).isEqualByComparingTo("0");
        assertThat(result.lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualByComparingTo("9.6");
            assertThat(l.total()).isEqualByComparingTo("1008.00");
        });
    }

    @Test
    @DisplayName("a reading over the accepted weight bills the accepted quantity: 1,050.00, never 10.4 kg's 1,092.00")
    void overweightIsBilledAtAccepted() {
        // The order stored the line at the billable quantity (10, the accepted), not the 10.4 on the scale.
        var result = InvoiceCalculator.calculate(order("0", "1050", "0", null, "1050"),
                List.of(weighed("10", "10", "1000", "50", "1050")), false);

        assertThat(result.total()).isEqualByComparingTo("1050.00");
        assertThat(result.lines().get(0).quantity()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("an inter-state supply is all IGST")
    void interState() {
        var result = InvoiceCalculator.calculate(order("0", "1050", "0", null, "1050"),
                List.of(weighed("10", null, "1000", "50", "1050")), true);

        assertThat(result.igst()).isEqualByComparingTo("50.00");
        assertThat(result.cgst()).isEqualByComparingTo("0");
        assertThat(result.sgst()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the delivery fee is its own amount, untaxed here, and part of the total")
    void deliveryFee() {
        var result = InvoiceCalculator.calculate(order("40", "1090", "0", null, "1090"),
                List.of(weighed("10", null, "1000", "50", "1050")), false);

        assertThat(result.deliveryFee()).isEqualByComparingTo("40.00");
        assertThat(result.total()).isEqualByComparingTo("1090.00");
        assertThat(result.taxable()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("after a doorstep rejection the invoice still states the supply as dispatched; the credit note reverses it")
    void afterDoorstepRejection() {
        // Final payable is 997.50 after Rs 10.50 came off at the door; the invoice is still the 1,008.00 supplied.
        var result = InvoiceCalculator.calculate(order("0", "1050", "42", "10.50", "997.50"),
                List.of(weighed("10", "9.6", "960", "48", "1008")), false);

        assertThat(result.total()).isEqualByComparingTo("1008.00");
    }

    @Test
    @DisplayName("an invoice that does not add up to what the order says was payable is refused, not written")
    void mismatchIsRefused() {
        assertThatThrownBy(() -> InvoiceCalculator.calculate(order("0", "1050", "42", null, "1008"),
                List.of(weighed("10", "10", "1000", "50", "1050")), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1050.00")
                .hasMessageContaining("1008.00");
    }

    @Test
    @DisplayName("a line nothing was supplied on is left off")
    void unsuppliedLineIsLeftOff() {
        var none = new Line(42L, "Paneer", "0406", d("0"), null, "KG", d("100"), d("5"),
                d("0"), d("0"), d("0"), null, null, null);

        var result = InvoiceCalculator.calculate(order("0", "1050", "0", null, "1050"),
                List.of(weighed("10", null, "1000", "50", "1050"), none), false);

        assertThat(result.lines()).hasSize(1);
    }
}
