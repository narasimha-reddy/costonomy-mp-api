package com.costonomy.mp.catalog;

import com.costonomy.mp.catalog.domain.Unit;
import com.costonomy.mp.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unit vocabulary.
 *
 * <p>This is a comparison-correctness test, not a formatting one. Two suppliers'
 * paneer map to one canonical product so a restaurant can hold their prices side
 * by side, and that is only true if both are quoting the same measure.
 */
class UnitTest {

    @ParameterizedTest
    @CsvSource({
            "kg,KG", "Kg,KG", " KG ,KG", "kgs,KG", "KILO,KG", "kilogram,KG",
            "l,LTR", "L,LTR", "litre,LTR", "LTRS,LTR",
            "piece,PC", "PIECES,PC", "pcs,PC", "nos,PC",
            "packet,PKT", "pack,PKT",
            "box,CASE", "carton,CASE",
            "g,GM", "gram,GM", "gms,GM",
    })
    @DisplayName("tolerates case, spacing and the spellings people actually type")
    void parsesAliases(String raw, String expected) {
        // An import that rejects a supplier's whole file over "Kg" is an import
        // nobody uses. Leniency at the edge, one spelling in the database.
        assertThat(Unit.parseOrNull(raw)).isEqualTo(Unit.valueOf(expected));
    }

    @Test
    @DisplayName("L and PIECE parse, because that is what the database held before")
    void parsesLegacySpellings() {
        assertThat(Unit.parseOrNull("L")).isEqualTo(Unit.LTR);
        assertThat(Unit.parseOrNull("PIECE")).isEqualTo(Unit.PC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PKT", "CASE", "BULK", "TIN", "BUNDLE"})
    @DisplayName("a container must say what is inside it")
    void containersNeedAMeasure(String name) {
        // "1 PKT" says how the goods are bundled and nothing about how much a
        // restaurant is buying.
        assertThat(Unit.valueOf(name).requiresMeasure()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"KG", "GM", "LTR", "ML", "PC", "DOZEN", "PAIR", "OZ", "LB", "BOTTLE"})
    @DisplayName("a unit that is already an amount does not")
    void amountsDoNot(String name) {
        // Including BOTTLE: "1 BOTTLE of 1 LTR" is worth saying but not required,
        // because a bottle is also a unit people quote on its own.
        assertThat(Unit.valueOf(name).requiresMeasure()).isFalse();
    }

    @Test
    @DisplayName("contents are measured in something a person can add up")
    void measuresAreCountable() {
        assertThat(Unit.measureUnits())
                .containsExactlyInAnyOrder(Unit.GM, Unit.KG, Unit.ML, Unit.LTR,
                        Unit.PC, Unit.BOTTLE, Unit.PKT);

        // "1 CASE of 2 BULK" is a riddle, not a quantity.
        assertThat(Unit.BULK.canMeasure()).isFalse();
        assertThat(Unit.CASE.canMeasure()).isFalse();
        assertThat(Unit.DOZEN.canMeasure()).isFalse();
    }

    @Test
    @DisplayName("PKT is both a pack and a measure, because a case of packets is ordinary")
    void packetIsBoth() {
        assertThat(Unit.PKT.requiresMeasure()).isTrue();
        assertThat(Unit.PKT.canMeasure()).isTrue();
    }

    @Test
    @DisplayName("an unknown unit is refused, and the error lists what is allowed")
    void refusesUnknown() {
        assertThat(Unit.parseOrNull("FIRKIN")).isNull();
        assertThatThrownBy(() -> Unit.parse("FIRKIN", "Pack unit"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Pack unit")
                .hasMessageContaining("KG");
    }

    @Test
    @DisplayName("every unit the vocabulary offers is one the enum can parse back")
    void vocabularyRoundTrips() {
        // The /catalog/units endpoint serves these names to clients. A name a
        // client can be offered but cannot send back is a broken form.
        assertThat(Unit.packUnits()).allSatisfy(
                unit -> assertThat(Unit.parseOrNull(unit.name())).isEqualTo(unit));
    }
}
