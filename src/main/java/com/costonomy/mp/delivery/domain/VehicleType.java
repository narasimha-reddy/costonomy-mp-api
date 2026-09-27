package com.costonomy.mp.delivery.domain;

import java.math.BigDecimal;

/**
 * Vehicle category required for a consignment based on package weight and volume thresholds.
 *
 * <ul>
 *   <li>{@link #TWO_WHEELER} (&le; 20 kg): Standard food/produce parcel for bike / 2W courier.</li>
 *   <li>{@link #THREE_WHEELER} (20 kg &ndash; 100 kg): Mid-sized bulk/catering for 3W auto cargo.</li>
 *   <li>{@link #FOUR_WHEELER_TRUCK} (&gt; 100 kg): Heavy wholesale consignment for 4W mini truck (Tata Ace / Eeco).</li>
 * </ul>
 */
public enum VehicleType {
    TWO_WHEELER(BigDecimal.ZERO, BigDecimal.valueOf(20)),
    THREE_WHEELER(BigDecimal.valueOf(20), BigDecimal.valueOf(100)),
    FOUR_WHEELER_TRUCK(BigDecimal.valueOf(100), BigDecimal.valueOf(10000));

    private final BigDecimal minWeightKg;
    private final BigDecimal maxWeightKg;

    VehicleType(BigDecimal minWeightKg, BigDecimal maxWeightKg) {
        this.minWeightKg = minWeightKg;
        this.maxWeightKg = maxWeightKg;
    }

    public BigDecimal minWeightKg() {
        return minWeightKg;
    }

    public BigDecimal maxWeightKg() {
        return maxWeightKg;
    }

    /**
     * Determines the optimal vehicle type for a total cargo weight in kg.
     *
     * @param weightKg total weight in kilograms; if null or non-positive, defaults to {@link #TWO_WHEELER}.
     * @return recommended vehicle type
     */
    public static VehicleType fromWeight(BigDecimal weightKg) {
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            return TWO_WHEELER;
        }
        if (weightKg.compareTo(BigDecimal.valueOf(20)) <= 0) {
            return TWO_WHEELER;
        }
        if (weightKg.compareTo(BigDecimal.valueOf(100)) <= 0) {
            return THREE_WHEELER;
        }
        return FOUR_WHEELER_TRUCK;
    }
}
