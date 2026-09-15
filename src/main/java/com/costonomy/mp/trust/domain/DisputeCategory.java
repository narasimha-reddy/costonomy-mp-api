package com.costonomy.mp.trust.domain;

import java.util.Arrays;

/**
 * Doc 01 §23 and doc 04 §16.
 *
 * <p>Seven categories, kept distinct because they are different commercial
 * problems with different answers: a short delivery is a quantity to make up, a
 * damaged one is stock to replace, an expired one is a handling failure, and a
 * wrong invoice is a number to correct. Collapsing them into "problem" would make
 * the dispute intelligence doc 01 §23 wants unreadable.
 */
public enum DisputeCategory {

    WRONG_PRODUCT,
    SHORT_QUANTITY,
    DAMAGED,
    EXPIRED,
    QUALITY,
    INCORRECT_INVOICE,
    OTHER;

    public static boolean isValid(String value) {
        return value != null && Arrays.stream(values())
                .anyMatch(category -> category.name().equals(value));
    }
}
