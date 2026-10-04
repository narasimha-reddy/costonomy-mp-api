package com.costonomy.mp.procurement;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.procurement.domain.CatchWeight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-124: Rs 100/kg, 5% GST, 10 kg accepted, so the agreed line is 1,000 + 50 = 1,050. */
class CatchWeightTest {

    private static final CatchWeight.Policy BAND = new CatchWeight.Policy(
            BigDecimal.valueOf(20), BigDecimal.valueOf(10));
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final BigDecimal GST = new BigDecimal("5.00");
    private static final BigDecimal TEN = new BigDecimal("10");

    private static CatchWeight.Billable bill(String unit, String reading) {
        return CatchWeight.bill(unit, PRICE, GST, TEN, new BigDecimal(reading), BAND);
    }

    @Test
    @DisplayName("The agreed line is the baseline")
    void baseline() {
        var b = CatchWeight.baseline(PRICE, GST, TEN);
        assertThat(b.lineValue()).isEqualByComparingTo("1000.00");
        assertThat(b.lineGst()).isEqualByComparingTo("50.00");
        assertThat(b.lineTotal()).isEqualByComparingTo("1050.00");
    }

    @Test
    @DisplayName("Underweight is billed at the weight on the scale")
    void underweightIsBilledAtActual() {
        var b = bill("KG", "9.6");
        assertThat(b.quantity()).isEqualByComparingTo("9.6");
        assertThat(b.lineValue()).isEqualByComparingTo("960.00");
        assertThat(b.lineGst()).isEqualByComparingTo("48.00");
        assertThat(b.lineTotal()).isEqualByComparingTo("1008.00");
    }

    @Test
    @DisplayName("Overweight within the band is billed at the accepted quantity; the supplier gives the rest away")
    void overweightWithinBandIsCappedAtAccepted() {
        var b = bill("KG", "10.4");
        assertThat(b.quantity()).isEqualByComparingTo("10");
        assertThat(b.lineTotal()).isEqualByComparingTo("1050.00");
    }

    @Test
    @DisplayName("Exactly the accepted quantity bills the baseline")
    void exactWeight() {
        assertThat(bill("KG", "10").lineTotal()).isEqualByComparingTo("1050.00");
    }

    @Test
    @DisplayName("The band edges are allowed: 8 kg (-20%) and 11 kg (+10%)")
    void bandEdgesAreInclusive() {
        assertThat(bill("KG", "8").quantity()).isEqualByComparingTo("8");
        assertThat(bill("KG", "11").quantity()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("A reading outside the band is refused, with the allowed range in the message")
    void outsideBandIsRefused() {
        assertThatThrownBy(() -> bill("KG", "12")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("8 to 11").hasMessageContaining("ordered 10");
        assertThatThrownBy(() -> bill("KG", "7.9")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("outside the allowed range");
    }

    @Test
    @DisplayName("A reading finer than a gram is refused")
    void tooManyDecimalsIsRefused() {
        assertThatThrownBy(() -> bill("KG", "9.6001")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("decimal places");
        assertThat(bill("KG", "9.600").quantity()).isEqualByComparingTo("9.6");
    }

    @Test
    @DisplayName("Zero, negative and missing readings are refused")
    void nonPositiveIsRefused() {
        assertThatThrownBy(() -> bill("KG", "0")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> bill("KG", "-1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CatchWeight.bill("KG", PRICE, GST, TEN, null, BAND))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Only mass units can be weighed; a packet or a litre cannot")
    void onlyMassUnits() {
        assertThatThrownBy(() -> bill("PKT", "10")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("sold by weight");
        assertThatThrownBy(() -> bill("LTR", "10")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> bill(null, "10")).isInstanceOf(BusinessException.class);
        assertThat(bill("kg", "9.6").lineTotal()).isEqualByComparingTo("1008.00");
    }

    @Test
    @DisplayName("A line with no accepted quantity cannot be weighed")
    void noAcceptedQuantity() {
        assertThatThrownBy(() -> CatchWeight.bill("KG", PRICE, GST, null, TEN, BAND))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no accepted quantity");
        assertThatThrownBy(() -> CatchWeight.bill("KG", PRICE, GST, BigDecimal.ZERO, TEN, BAND))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("A gram-priced line works the same: 9,600 of 10,000 GM")
    void gramLine() {
        var b = CatchWeight.bill("GM", new BigDecimal("0.10"), GST, new BigDecimal("10000"),
                new BigDecimal("9600"), BAND);
        assertThat(b.lineValue()).isEqualByComparingTo("960.00");
        assertThat(b.lineTotal()).isEqualByComparingTo("1008.00");
    }
}
