package com.costonomy.mp.discovery.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;

/**
 * Checks for the filters on the buyer's supplier lists (D-181), shared by the directory and the popular list so both
 * refuse the same values.
 */
final class SupplierListFilters {

    private SupplierListFilters() {
    }

    /** {@code nearest} (the default) or {@code rating}; anything else is a 422. Returned lower-case. */
    static String requireSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "nearest";
        }
        String normalized = sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("nearest") && !normalized.equals("rating")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Sort must be nearest or rating.");
        }
        return normalized;
    }

    /** A star rating from 1 to 5, or null for no minimum. Anything else is a 422 rather than a list that is always empty. */
    static Integer requireMinRating(Integer minRating) {
        if (minRating != null && (minRating < 1 || minRating > 5)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Minimum rating must be from 1 to 5.");
        }
        return minRating;
    }
}
