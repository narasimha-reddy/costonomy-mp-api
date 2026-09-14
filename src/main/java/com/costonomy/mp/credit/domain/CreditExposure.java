package com.costonomy.mp.credit.domain;

import java.math.BigDecimal;

/**
 * The six numbers doc 01 §18 and doc 04 §13 insist stay distinct.
 *
 * <p>{@code available = approvedLimit - reserved - utilized}, and the identity
 * {@code approvedLimit = reserved + utilized + available} (doc 10 §3) follows from
 * it. The point of the type is that those numbers are computed in exactly one
 * place: a screen, an API response and a limit check that each derive "available"
 * their own way will eventually disagree, and the restaurant will find out at the
 * checkout.
 *
 * <p><b>{@code available} is never stored.</b> A persisted copy is a second source
 * of truth for a number already implied by three others, and it goes stale the
 * first time a write partially fails.
 *
 * <p>{@code due} and {@code overdue} come from invoices rather than the agreement
 * row, because they are statements about time passing rather than about the
 * limit. They do not enter the availability arithmetic — an overdue invoice is
 * already counted in {@code utilized}; subtracting it again would charge the
 * restaurant twice for one debt.
 */
public record CreditExposure(
        BigDecimal approvedLimit,
        BigDecimal reserved,
        BigDecimal utilized,
        BigDecimal due,
        BigDecimal overdue) {

    public static final CreditExposure NONE = new CreditExposure(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);

    public BigDecimal available() {
        var available = approvedLimit.subtract(reserved).subtract(utilized);
        // Clamped rather than returned negative. A negative "available" is not a
        // number anyone can act on, and doc 01 §18 says it cannot happen — the
        // database CHECK enforces that, so this only guards a caller that built an
        // exposure by hand.
        return available.signum() < 0 ? BigDecimal.ZERO : available;
    }

    /** Whether {@code amount} fits in what is left. */
    public boolean canCover(BigDecimal amount) {
        return available().compareTo(amount) >= 0;
    }

    public CreditExposure plus(CreditExposure other) {
        return new CreditExposure(
                approvedLimit.add(other.approvedLimit),
                reserved.add(other.reserved),
                utilized.add(other.utilized),
                due.add(other.due),
                overdue.add(other.overdue));
    }
}
