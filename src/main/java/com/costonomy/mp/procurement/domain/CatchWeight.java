package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * What a weighed catch-weight line is billed for (D-128).
 *
 * <p>Pure, and the only place the rule lives. A line is sold by weight but ordered as an estimate, so
 * the scale reading decides the bill, with two limits:
 * <ul>
 *   <li><b>The buyer never pays for more than they agreed to.</b> The billable quantity is
 *       {@code min(reading, accepted)}. Overweight within the band is the supplier's giveaway: billing it
 *       would need money beyond what a card was authorised for, and the price the restaurant saw was one
 *       number.</li>
 *   <li><b>A reading outside the band is refused.</b> Far from the ordered weight, a reading is more
 *       likely a mistyped scale than a real parcel, and silently billing it either way is how a typo
 *       becomes a refund.</li>
 * </ul>
 * All money goes through {@link Pricing}, so a weighed line's figures agree to the paisa with an
 * unweighed one's.
 */
public final class CatchWeight {

    /** Mass units: the reading is taken in the line's own unit, so no conversion can go wrong. */
    private static final Set<String> MASS_UNITS = Set.of("GM", "KG", "OZ", "LB");

    /** A scale reads to the gram. */
    private static final int MAX_DECIMALS = 3;

    private CatchWeight() {
    }

    /**
     * @param maxUnderPercent how far below the accepted quantity a reading may be (20 means -20%)
     * @param maxOverPercent  how far above it (10 means +10%)
     */
    public record Policy(BigDecimal maxUnderPercent, BigDecimal maxOverPercent) {
    }

    /**
     * @param quantity  what the buyer is billed for
     * @param lineValue {@code quantity} at the line's unit price, before GST
     * @param lineGst   GST on {@code lineValue}
     * @param lineTotal {@code lineValue + lineGst}
     */
    public record Billable(BigDecimal quantity, BigDecimal lineValue, BigDecimal lineGst, BigDecimal lineTotal) {
    }

    /** The line as agreed, before any weighing. */
    public static Billable baseline(BigDecimal unitPrice, BigDecimal gstRate, BigDecimal accepted) {
        return figures(unitPrice, gstRate, accepted);
    }

    public static Billable bill(String unit, BigDecimal unitPrice, BigDecimal gstRate,
                                BigDecimal accepted, BigDecimal reading, Policy policy) {
        if (unit == null || !MASS_UNITS.contains(unit.trim().toUpperCase(Locale.ROOT))) {
            throw invalid("Only lines sold by weight (GM, KG, OZ, LB) can be weighed; this one is sold by "
                    + (unit == null ? "an unknown unit" : unit) + ".");
        }
        if (accepted == null || accepted.signum() <= 0) {
            throw invalid("This line has no accepted quantity to weigh against.");
        }
        if (reading == null || reading.signum() <= 0) {
            throw invalid("Enter the weight shown on the scale.");
        }
        if (reading.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw invalid("A scale reading has at most %d decimal places.".formatted(MAX_DECIMALS));
        }

        BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal floor = accepted.multiply(hundred.subtract(policy.maxUnderPercent())).divide(hundred);
        BigDecimal ceiling = accepted.multiply(hundred.add(policy.maxOverPercent())).divide(hundred);
        if (reading.compareTo(floor) < 0 || reading.compareTo(ceiling) > 0) {
            throw invalid("%s is outside the allowed range of %s to %s for this line (ordered %s). "
                    .formatted(reading.stripTrailingZeros().toPlainString(),
                            floor.stripTrailingZeros().toPlainString(),
                            ceiling.stripTrailingZeros().toPlainString(),
                            accepted.stripTrailingZeros().toPlainString())
                    + "Check the scale, or ask the buyer to cancel and re-order.");
        }

        return figures(unitPrice, gstRate, reading.min(accepted));
    }

    private static Billable figures(BigDecimal unitPrice, BigDecimal gstRate, BigDecimal quantity) {
        BigDecimal value = Pricing.lineItemValue(unitPrice, quantity);
        BigDecimal gst = Pricing.lineGst(value, gstRate);
        return new Billable(quantity, value, gst, Pricing.lineTotal(value, gst));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
