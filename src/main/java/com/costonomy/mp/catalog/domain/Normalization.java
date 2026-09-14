package com.costonomy.mp.catalog.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Text normalisation for catalog matching. Doc 07 §2.
 *
 * <p>Normalised forms are computed once at write time and stored
 * ({@code normalized_name}, {@code normalized_alias}, {@code brand.normalized_name}),
 * not computed per query. That is what lets those columns be indexed, and it
 * means a lookup and an insert can never disagree about what "the same name"
 * means — the usual failure when normalisation lives at the call site.
 *
 * <p>Deliberately conservative. It folds case, accents, punctuation and spacing.
 * It does <b>not</b> stem, pluralise or translate: doc 07 §2 forbids inventing
 * semantic mappings without a configured alias, so "dahi" finds curd because
 * somebody put it in {@code canonical_product_alias}, not because this function
 * guessed.
 */
public final class Normalization {

    private Normalization() {
    }

    /**
     * {@code "  Amul  PANEER (1 Kg) "} → {@code "amul paneer 1 kg"}.
     *
     * <p>Unicode NFD plus mark-stripping folds accents, so a supplier's
     * "Purée" matches a restaurant's "puree".
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);

        // Anything that is not a letter, digit or space becomes a space, then runs
        // of space collapse. Keeps "1kg" and "1 kg" apart from each other but
        // both distinct from "1-kg" only in spacing, which the collapse then fixes.
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** True when two names normalise to the same thing. */
    public static boolean sameName(String a, String b) {
        return normalize(a).equals(normalize(b));
    }
}
