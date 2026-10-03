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

    public boolean canCarryColdChain() {
        return this != TWO_WHEELER;
    }

    /**
     * Determines the optimal vehicle type for a total cargo weight in kg.
     *
     * @param weightKg total weight in kilograms; if null or non-positive, defaults to {@link #TWO_WHEELER}.
     * @return recommended vehicle type
     */
    public static VehicleType fromWeight(BigDecimal weightKg) {
        return fromWeight(weightKg, false);
    }

    /**
     * Determines the optimal vehicle type considering both weight and cold-chain temperature requirements.
     * Open 2-wheelers cannot maintain cold-chain; temperature-sensitive consignments require at least an
     * enclosed/insulated {@link #THREE_WHEELER} or {@link #FOUR_WHEELER_TRUCK}.
     *
     * @param weightKg total weight in kilograms
     * @param requiresColdChain whether consignment contains temperature-sensitive items
     * @return recommended vehicle type
     */
    public static VehicleType fromWeight(BigDecimal weightKg, boolean requiresColdChain) {
        if (requiresColdChain) {
            if (weightKg != null && weightKg.compareTo(BigDecimal.valueOf(100)) > 0) {
                return FOUR_WHEELER_TRUCK;
            }
            return THREE_WHEELER; // Enclosed / insulated 3W cargo box minimum for cold chain
        }

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

