package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxStages;
import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxStages.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** D-190: the sandbox rider's position is worked out along the pickup to drop line, not Pidge's fixed dummy point. */
class PidgeSandboxPositionTest {

    // Koramangala store to Indiranagar outlet, about 5.4 km apart.
    private static final BigDecimal P_LAT = new BigDecimal("12.9352000");
    private static final BigDecimal P_LNG = new BigDecimal("77.6245000");
    private static final BigDecimal D_LAT = new BigDecimal("12.9716000");
    private static final BigDecimal D_LNG = new BigDecimal("77.5946000");

    private static Position at(String dummyStatus) {
        return PidgeSandboxStages.position(dummyStatus, P_LAT, P_LNG, D_LAT, D_LNG).orElseThrow();
    }

    private static double metres(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371008.8;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static double fromPickup(Position p) {
        return metres(P_LAT.doubleValue(), P_LNG.doubleValue(), p.latitude().doubleValue(), p.longitude().doubleValue());
    }

    private static double fromDrop(Position p) {
        return metres(D_LAT.doubleValue(), D_LNG.doubleValue(), p.latitude().doubleValue(), p.longitude().doubleValue());
    }

    @Test
    @DisplayName("reached pickup and picked up are at the pickup; delivered is at the drop")
    void endsAreTheEnds() {
        assertThat(fromPickup(at("fulfilled|reached pickup"))).isLessThan(1.0);
        assertThat(fromPickup(at("fulfilled|picked up"))).isLessThan(1.0);
        assertThat(fromDrop(at("fulfilled|delivered"))).isLessThan(1.0);
    }

    @Test
    @DisplayName("out for pickup is about 2 km short of the pickup, on the far side from the drop")
    void outForPickupIsShortOfThePickup() {
        var p = at("fulfilled|out for pickup");
        assertThat(fromPickup(p)).isBetween(1990.0, 2010.0);
        assertThat(fromDrop(p)).isGreaterThan(metres(P_LAT.doubleValue(), P_LNG.doubleValue(),
                D_LAT.doubleValue(), D_LNG.doubleValue()));
    }

    @Test
    @DisplayName("ofd is 40 percent of the way, strictly between pickup and drop")
    void ofdIsFortyPercent() {
        var p = at("fulfilled|ofd");
        double total = metres(P_LAT.doubleValue(), P_LNG.doubleValue(), D_LAT.doubleValue(), D_LNG.doubleValue());
        assertThat(fromPickup(p)).isBetween(total * 0.39, total * 0.41);
        assertThat(fromPickup(p) + fromDrop(p)).isBetween(total * 0.999, total * 1.001);
        assertThat(p.latitude()).isStrictlyBetween(P_LAT, D_LAT);
        assertThat(p.longitude()).isStrictlyBetween(D_LNG, P_LNG);
    }

    @Test
    @DisplayName("reached delivery is within 50 m of the drop (about 30 m) but not on it, so 'reached' and 'arriving' show")
    void reachedDeliveryIsNearTheDrop() {
        var p = at("fulfilled|reached delivery");
        assertThat(fromDrop(p)).isBetween(20.0, 40.0);
    }

    @Test
    @DisplayName("the journey only ever moves forward: out for pickup, pickup, pickup, ofd, near drop, drop")
    void monotonicAlongTheRoute() {
        String[] stages = {"fulfilled|out for pickup", "fulfilled|reached pickup", "fulfilled|picked up",
                "fulfilled|ofd", "fulfilled|reached delivery", "fulfilled|delivered"};
        double last = Double.NEGATIVE_INFINITY;
        for (String s : stages) {
            // Distance along the line from the pickup; the first stage is short of it (negative).
            double along = (s.contains("out for") ? -1 : 1) * fromPickup(at(s));
            assertThat(along).describedAs(s).isGreaterThanOrEqualTo(last - 1.0);
            last = along;
        }
    }

    @Test
    @DisplayName("seven decimals, so it fits delivery_location DECIMAL(10,7)")
    void sevenDecimals() {
        for (String s : new String[] {"fulfilled|out for pickup", "fulfilled|ofd", "fulfilled|reached delivery"}) {
            var p = at(s);
            assertThat(p.latitude().scale()).describedAs(s).isEqualTo(7);
            assertThat(p.longitude().scale()).describedAs(s).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("a stage not in the table, or a missing coordinate, gives no position (the old behaviour applies)")
    void noPositionFallsBack() {
        assertThat(PidgeSandboxStages.position("fulfilled|unknown", P_LAT, P_LNG, D_LAT, D_LNG)).isEmpty();
        assertThat(PidgeSandboxStages.position("fulfilled|ofd", null, P_LNG, D_LAT, D_LNG)).isEmpty();
        assertThat(PidgeSandboxStages.position("fulfilled|ofd", P_LAT, P_LNG, D_LAT, null)).isEmpty();
    }

    @Test
    @DisplayName("pickup and drop on the same point: every stage is that point, no division by zero")
    void samePoint() {
        var p = PidgeSandboxStages.position("fulfilled|reached delivery", P_LAT, P_LNG, P_LAT, P_LNG).orElseThrow();
        assertThat(p.latitude()).isEqualByComparingTo(P_LAT);
        assertThat(p.longitude()).isEqualByComparingTo(P_LNG);
    }

    @Test
    @DisplayName("a drop closer than 30 m is clamped to the pickup rather than running past it")
    void clampsOnShortRoutes() {
        var near = new BigDecimal("12.9352100"); // about 11 m
        var p = PidgeSandboxStages.position("fulfilled|reached delivery", P_LAT, P_LNG, near, P_LNG).orElseThrow();
        assertThat(p.latitude()).isBetween(P_LAT, near);
    }
}
