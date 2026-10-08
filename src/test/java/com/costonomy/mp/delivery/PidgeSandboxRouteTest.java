package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute;
import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxStages;
import com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute.Leg;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** D-192: the sandbox rider follows a stored real road route, not the straight line. */
class PidgeSandboxRouteTest {

    private final PidgeSandboxRoute route = PidgeSandboxRoute.shared();

    private static double metres(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double a = Math.pow(Math.sin((p2 - p1) / 2), 2)
                + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(lng2 - lng1) / 2), 2);
        return 2 * 6371008.8 * Math.asin(Math.sqrt(a));
    }

    private static double[] d(PidgeSandboxRoute.Point p) {
        return new double[] {p.latitude().doubleValue(), p.longitude().doubleValue()};
    }

    @Test
    void cumulativeDistancesAreStrictlyIncreasingAndEndAtTheLegLength() {
        for (Leg leg : Leg.values()) {
            double[] cum = route.cumulativeMetres(leg);
            assertThat(cum[0]).isZero();
            for (int i = 1; i < cum.length; i++) {
                assertThat(cum[i]).isGreaterThan(cum[i - 1]);
            }
            assertThat(cum[cum.length - 1]).isEqualTo(route.lengthMetres(leg));
        }
        assertThat(route.lengthMetres(Leg.APPROACH)).isBetween(1500.0, 3500.0);
        assertThat(route.lengthMetres(Leg.DELIVERY)).isBetween(1500.0, 3500.0);
    }

    @Test
    void zeroIsTheFirstPointAndOneIsTheLastAndItClamps() {
        var points = route.points(Leg.APPROACH);
        assertThat(d(route.pointAt(Leg.APPROACH, 0.0))).containsExactly(round(points.get(0)[0]), round(points.get(0)[1]));
        var last = points.get(points.size() - 1);
        assertThat(d(route.pointAt(Leg.APPROACH, 1.0))).containsExactly(round(last[0]), round(last[1]));
        assertThat(d(route.pointAt(Leg.APPROACH, 7.0))).containsExactly(round(last[0]), round(last[1]));
        assertThat(d(route.pointAt(Leg.APPROACH, -3.0))).containsExactly(round(points.get(0)[0]), round(points.get(0)[1]));
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(7, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    @Test
    void halfIsHalfWayByDistanceAndHasSevenDecimals() {
        for (Leg leg : Leg.values()) {
            var half = route.pointAt(leg, 0.5);
            assertThat(half.latitude().scale()).isEqualTo(7);
            assertThat(half.longitude().scale()).isEqualTo(7);
            var points = route.points(leg);
            var first = points.get(0);
            double along = pathMetresTo(leg, half);
            assertThat(along).isBetween(route.lengthMetres(leg) * 0.5 - 3, route.lengthMetres(leg) * 0.5 + 3);
            assertThat(metres(first[0], first[1], half.latitude().doubleValue(), half.longitude().doubleValue()))
                    .isGreaterThan(100.0);
        }
    }

    /** Path distance from the leg start to a point that lies on the polyline. */
    private double pathMetresTo(Leg leg, PidgeSandboxRoute.Point p) {
        var pts = route.points(leg);
        double[] cum = route.cumulativeMetres(leg);
        double best = Double.MAX_VALUE;
        double at = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            double a = metres(pts.get(i)[0], pts.get(i)[1], p.latitude().doubleValue(), p.longitude().doubleValue());
            double b = metres(p.latitude().doubleValue(), p.longitude().doubleValue(), pts.get(i + 1)[0], pts.get(i + 1)[1]);
            double seg = cum[i + 1] - cum[i];
            double off = Math.abs(a + b - seg);
            if (off < best) {
                best = off;
                at = cum[i] + a;
            }
        }
        return at;
    }

    @Test
    void thirtyMetresFromTheEndIsThirtyMetresFromTheEnd() {
        var p = route.pointAtMetersFromEnd(Leg.DELIVERY, 30);
        var end = route.pointAt(Leg.DELIVERY, 1.0);
        double m = metres(p.latitude().doubleValue(), p.longitude().doubleValue(),
                end.latitude().doubleValue(), end.longitude().doubleValue());
        assertThat(m).isBetween(25.0, 35.0);
        // More than the whole leg clamps to the start.
        assertThat(d(route.pointAtMetersFromEnd(Leg.DELIVERY, 1e9))).isEqualTo(d(route.pointAt(Leg.DELIVERY, 0.0)));
    }

    @Test
    void matchesTheSeedPairAndNothingFar() {
        assertThat(route.matches(new BigDecimal("12.9611"), new BigDecimal("77.6387"),
                new BigDecimal("12.9784"), new BigDecimal("77.6408"))).isTrue();
        // Koramangala to Indiranagar (the older test pair) is kilometres away.
        assertThat(route.matches(new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("12.9716"), new BigDecimal("77.5946"))).isFalse();
        // Pickup right, drop far.
        assertThat(route.matches(new BigDecimal("12.9611"), new BigDecimal("77.6387"),
                new BigDecimal("12.9716"), new BigDecimal("77.5946"))).isFalse();
        // Swapped.
        assertThat(route.matches(new BigDecimal("12.9784"), new BigDecimal("77.6408"),
                new BigDecimal("12.9611"), new BigDecimal("77.6387"))).isFalse();
        assertThat(route.matches(null, null, null, null)).isFalse();
    }

    @Test
    void stagesFollowTheRoad() {
        var out = PidgeSandboxStages.roadPosition("fulfilled|out for pickup", route).orElseThrow();
        assertThat(out.latitude()).isEqualByComparingTo(route.pointAt(Leg.APPROACH, 0).latitude());
        var reached = PidgeSandboxStages.roadPosition("fulfilled|reached pickup", route).orElseThrow();
        var picked = PidgeSandboxStages.roadPosition("fulfilled|picked up", route).orElseThrow();
        assertThat(reached).isEqualTo(picked);
        assertThat(metres(reached.latitude().doubleValue(), reached.longitude().doubleValue(), 12.9611, 77.6387))
                .isLessThan(50.0);
        var ofd = PidgeSandboxStages.roadPosition("fulfilled|ofd", route).orElseThrow();
        assertThat(ofd).isEqualTo(new PidgeSandboxStages.Position(
                route.pointAt(Leg.DELIVERY, 0.35).latitude(), route.pointAt(Leg.DELIVERY, 0.35).longitude()));
        var near = PidgeSandboxStages.roadPosition("fulfilled|reached delivery", route).orElseThrow();
        var done = PidgeSandboxStages.roadPosition("fulfilled|delivered", route).orElseThrow();
        assertThat(metres(near.latitude().doubleValue(), near.longitude().doubleValue(),
                done.latitude().doubleValue(), done.longitude().doubleValue())).isBetween(25.0, 35.0);
        assertThat(PidgeSandboxStages.roadPosition("nope", route)).isEmpty();
    }

    @Test
    void straightMoveFallbackRunsFromTwoKmBeforeThePickup() {
        var p = new BigDecimal("12.9352000");
        var q = new BigDecimal("77.6245000");
        var start = PidgeSandboxStages.straightMove(Leg.APPROACH, 0, p, q, new BigDecimal("12.9716"), new BigDecimal("77.5946"))
                .orElseThrow();
        assertThat(metres(12.9352, 77.6245, start.latitude().doubleValue(), start.longitude().doubleValue()))
                .isBetween(1990.0, 2010.0);
        var end = PidgeSandboxStages.straightMove(Leg.APPROACH, 1, p, q, new BigDecimal("12.9716"), new BigDecimal("77.5946"))
                .orElseThrow();
        assertThat(end.latitude()).isEqualByComparingTo(p);
        var mid = PidgeSandboxStages.straightMove(Leg.DELIVERY, 0.5, p, q, new BigDecimal("12.9716"), new BigDecimal("77.5946"))
                .orElseThrow();
        assertThat(mid.latitude()).isEqualByComparingTo("12.9534000");
    }
}
