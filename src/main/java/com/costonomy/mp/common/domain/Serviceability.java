package com.costonomy.mp.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Whether a supplier store can deliver to an outlet. Doc 07 §13, doc 41.
 *
 * <p>Geography first, with supplier configuration on top: distance within the
 * store's radius, unless the store has declared an explicit pincode list, in
 * which case that list decides.
 *
 * <p>This is the <em>discovery-time</em> answer. Doc 41 requires final order
 * validation to re-check serviceability, because a store can go offline or change
 * its radius between a restaurant browsing and placing an order.
 */
public final class Serviceability {

    private Serviceability() {
    }

    /** Mean Earth radius, kilometres. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * Great-circle distance between two points, in kilometres.
     *
     * <p>Haversine. Accurate to well under a percent at city scale, which is far
     * inside the error of assuming a straight line between two addresses in the
     * first place — the real journey follows roads. It is used for radius filtering
     * and a first-pass ETA, and is replaced by a provider's actual route once
     * delivery quoting lands (Phase 11).
     *
     * @return kilometres, or {@code null} if either point is unknown
     */
    public static Double distanceKm(BigDecimal lat1, BigDecimal lon1,
                                    BigDecimal lat2, BigDecimal lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return null;
        }

        double φ1 = Math.toRadians(lat1.doubleValue());
        double φ2 = Math.toRadians(lat2.doubleValue());
        double Δφ = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double Δλ = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());

        double a = Math.sin(Δφ / 2) * Math.sin(Δφ / 2)
                + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) * Math.sin(Δλ / 2);

        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Estimated minutes from order placement to delivery.
     *
     * <p>Preparation + transit + a fixed dispatch allowance. Deliberately coarse:
     * it exists to <em>order</em> candidates, not to promise a time. The concrete
     * "Arriving by 3:30 PM" §23A.21 requires comes from a delivery provider's own
     * estimate once one is assigned, and that number is authoritative over this one.
     *
     * @return minutes, or {@code null} when distance is unknown — the caller must
     *         then treat ETA as absent rather than assuming a value
     */
    public static Integer estimateMinutes(Integer preparationMinutes, Double distanceKm,
                                          BigDecimal averageSpeedKmph, int dispatchOverheadMinutes) {
        if (distanceKm == null || averageSpeedKmph == null || averageSpeedKmph.signum() <= 0) {
            return null;
        }
        int preparation = preparationMinutes == null ? 0 : preparationMinutes;
        double transitMinutes = (distanceKm / averageSpeedKmph.doubleValue()) * 60.0;
        return preparation + dispatchOverheadMinutes + (int) Math.ceil(transitMinutes);
    }

    /** Round a distance for display. */
    public static BigDecimal round(Double km) {
        return km == null ? null : BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP);
    }
}
