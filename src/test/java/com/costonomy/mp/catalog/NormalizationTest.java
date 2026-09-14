package com.costonomy.mp.catalog;

import com.costonomy.mp.catalog.domain.Normalization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationTest {

    @Test
    @DisplayName("folds case, punctuation and spacing")
    void foldsSurfaceDifferences() {
        // The same product typed four ways by four suppliers.
        assertThat(Normalization.normalize("  Amul  PANEER (1 Kg) "))
                .isEqualTo("amul paneer 1 kg");
        assertThat(Normalization.normalize("Amul Paneer 1kg"))
                .isEqualTo("amul paneer 1kg");
        assertThat(Normalization.normalize("REFINED-WHEAT-FLOUR"))
                .isEqualTo("refined wheat flour");
    }

    @Test
    @DisplayName("folds accents")
    void foldsAccents() {
        assertThat(Normalization.normalize("Purée")).isEqualTo("puree");
    }

    @Test
    @DisplayName("does not stem, pluralise or translate")
    void doesNotInventMeaning() {
        // Doc 07 §2 forbids inventing semantic mappings without a configured
        // alias. "Tomatoes" finds Tomato because someone adds an alias, not
        // because this function guessed a stem — a guesser would also decide that
        // "tomato ketchup" is tomatoes.
        assertThat(Normalization.normalize("Tomatoes")).isNotEqualTo(Normalization.normalize("Tomato"));
        assertThat(Normalization.normalize("Dahi")).isNotEqualTo(Normalization.normalize("Curd"));
    }

    @Test
    @DisplayName("handles null and blank without throwing")
    void handlesEmpty() {
        assertThat(Normalization.normalize(null)).isEmpty();
        assertThat(Normalization.normalize("   ")).isEmpty();
        assertThat(Normalization.normalize("!!!")).isEmpty();
    }

    @Test
    @DisplayName("sameName compares normalised forms")
    void sameNameCompares() {
        assertThat(Normalization.sameName("Amul", " amul ")).isTrue();
        assertThat(Normalization.sameName("Amul", "Amul Gold")).isFalse();
    }
}
