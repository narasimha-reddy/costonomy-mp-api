package com.costonomy.mp.catalog;

import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.domain.ImportValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The value coercions a real supplier spreadsheet needs.
 *
 * <p>These look trivial and are the difference between an import that works and
 * one that rejects half a supplier's price list over currency symbols.
 */
class ImportParsingTest {

    @Nested
    @DisplayName("decimals")
    class Decimals {

        @Test
        @DisplayName("accepts the ways a spreadsheet writes money")
        void acceptsFormattedMoney() {
            assertThat(ImportValues.parseDecimal("1450")).isEqualByComparingTo("1450");
            assertThat(ImportValues.parseDecimal("1450.50")).isEqualByComparingTo("1450.50");
            assertThat(ImportValues.parseDecimal("₹1,450.00")).isEqualByComparingTo("1450.00");
            assertThat(ImportValues.parseDecimal(" 1450 ")).isEqualByComparingTo("1450");
            assertThat(ImportValues.parseDecimal("5%")).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("returns null for a non-number rather than guessing zero")
        void rejectsNonNumbers() {
            // A price that silently becomes 0 is a supplier selling at nothing.
            // Null becomes a row-level error the supplier can see and fix.
            assertThat(ImportValues.parseDecimal("call for price")).isNull();
            assertThat(ImportValues.parseDecimal("N/A")).isNull();
            assertThat(ImportValues.parseDecimal("")).isNull();
            assertThat(ImportValues.parseDecimal(null)).isNull();
        }

        @Test
        @DisplayName("keeps full precision")
        void keepsPrecision() {
            // DECIMAL(19,4) all the way through; no double anywhere on this path.
            assertThat(ImportValues.parseDecimal("1450.1234"))
                    .isEqualTo(new BigDecimal("1450.1234"));
        }
    }

    @Nested
    @DisplayName("availability")
    class Availability {

        @ParameterizedTest
        @ValueSource(strings = {"AVAILABLE", "available", "In Stock", "yes", "Y", "TRUE", "1", "active"})
        @DisplayName("accepts the ways a spreadsheet says yes")
        void acceptsAvailable(String input) {
            assertThat(ImportValues.normalizeAvailability(input))
                    .isEqualTo(SupplierOffer.Availability.AVAILABLE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"OUT_OF_STOCK", "out of stock", "no", "N", "false", "0", "inactive"})
        @DisplayName("accepts the ways a spreadsheet says no")
        void acceptsUnavailable(String input) {
            assertThat(ImportValues.normalizeAvailability(input))
                    .isEqualTo(SupplierOffer.Availability.OUT_OF_STOCK);
        }

        @Test
        @DisplayName("a missing column means everything on the list is for sale")
        void defaultsToAvailable() {
            // A plain price list has no availability column, and every row on it
            // is on offer.
            assertThat(ImportValues.normalizeAvailability(null))
                    .isEqualTo(SupplierOffer.Availability.AVAILABLE);
            assertThat(ImportValues.normalizeAvailability("  "))
                    .isEqualTo(SupplierOffer.Availability.AVAILABLE);
        }

        @Test
        @DisplayName("an unrecognised value is an error, not a guess")
        void rejectsUnknownValues() {
            // Guessing could put something out of stock in front of a restaurant,
            // which becomes a supplier rejection and a wasted 60-second SLA.
            assertThat(ImportValues.normalizeAvailability("maybe")).isNull();
            assertThat(ImportValues.normalizeAvailability("limited")).isNull();
        }
    }
}
