package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * The Pidge sandbox has no riders, so a test moves a delivery along by asking Pidge's own dummy endpoint for the
 * next stage (D-188). This is the table of which stage follows which delivery status. Pure and test-only: nothing
 * here is reachable unless {@link PidgeProperties#isSandbox()} is on.
 */
public final class PidgeSandboxStages {

    private PidgeSandboxStages() {
    }

    /** The Pidge {@code dummy_status} that moves a delivery in this status forward one step. */
    public static Optional<String> nextDummyStatus(DeliveryStatus status) {
        return Optional.ofNullable(switch (status) {
            case PROVIDER_SELECTED -> "fulfilled|out for pickup";
            case DRIVER_ASSIGNED -> "fulfilled|reached pickup";
            case DRIVER_AT_PICKUP -> "fulfilled|picked up";
            case PICKED_UP -> "fulfilled|ofd";
            case IN_TRANSIT -> "fulfilled|reached delivery";
            case ARRIVED_AT_DESTINATION -> "fulfilled|delivered";
            default -> null;
        });
    }

    /** True when the sandbox controls apply to this delivery: Pidge, sandbox on, our own booking, a next stage. */
    public static boolean applies(PidgeProperties properties, Delivery delivery) {
        return properties.isSandbox()
                && isPidge(delivery)
                && delivery.getProviderDeliveryId() != null
                && delivery.getMode() == DeliveryMode.COSTONOMY
                && nextDummyStatus(delivery.getStatus()).isPresent();
    }

    public static boolean isPidge(Delivery delivery) {
        return PidgeDeliveryProvider.CODE.equals(delivery.getProviderCode());
    }

    /** A rider position, 7 decimals (the width of delivery_location). */
    public record Position(BigDecimal latitude, BigDecimal longitude) {
    }

    /**
     * Where the simulated rider is at each stage, as a fraction of the straight line from pickup (0) to drop (1),
     * or a fixed distance from an end. Pidge's dummy answer always carries one fixed point near Gurugram, far from
     * any Bengaluru route, so the sandbox replaces it with a point on the line (D-190).
     */
    private record Spot(double fraction, double metresShortOfPickup, double metresShortOfDrop) {
        static Spot along(double fraction) {
            return new Spot(fraction, 0, 0);
        }

        static Spot shortOfPickup(double metres) {
            return new Spot(0, metres, 0);
        }

        static Spot shortOfDrop(double metres) {
            return new Spot(1, 0, metres);
        }
    }

    private static final Map<String, Spot> SPOTS = Map.of(
            "fulfilled|out for pickup", Spot.shortOfPickup(2000),
            "fulfilled|reached pickup", Spot.along(0.0),
            "fulfilled|picked up", Spot.along(0.0),
            "fulfilled|ofd", Spot.along(0.4),
            "fulfilled|reached delivery", Spot.shortOfDrop(30),
            "fulfilled|delivered", Spot.along(1.0));

    private static final double METRES_PER_DEGREE = 111_320.0;

    /**
     * The synthetic rider position for a dummy stage, or empty when the stage is not in the table or the delivery
     * has no stored pickup or drop (the caller then keeps what Pidge sent).
     */
    public static Optional<Position> position(String dummyStatus, BigDecimal pickupLat, BigDecimal pickupLng,
                                              BigDecimal dropLat, BigDecimal dropLng) {
        var spot = dummyStatus == null ? null : SPOTS.get(dummyStatus);
        if (spot == null || pickupLat == null || pickupLng == null || dropLat == null || dropLng == null) {
            return Optional.empty();
        }
        double pLat = pickupLat.doubleValue();
        double pLng = pickupLng.doubleValue();
        double dLat = dropLat.doubleValue();
        double dLng = dropLng.doubleValue();
        // Local flat-earth metres: plenty for a city-sized line.
        double cos = Math.cos(Math.toRadians(pLat));
        double dy = (dLat - pLat) * METRES_PER_DEGREE;
        double dx = (dLng - pLng) * METRES_PER_DEGREE * cos;
        double length = Math.hypot(dx, dy);

        double lat;
        double lng;
        if (length < 0.5) {
            lat = pLat;
            lng = pLng;
        } else if (spot.metresShortOfPickup() > 0) {
            double back = spot.metresShortOfPickup() / length;
            lat = pLat - (dLat - pLat) * back;
            lng = pLng - (dLng - pLng) * back;
        } else {
            double f = spot.fraction() - spot.metresShortOfDrop() / length;
            f = Math.max(0.0, Math.min(1.0, f));
            lat = pLat + (dLat - pLat) * f;
            lng = pLng + (dLng - pLng) * f;
        }
        return Optional.of(new Position(scale7(lat), scale7(lng)));
    }

    private static BigDecimal scale7(double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP);
    }
}
