package com.costonomy.mp.delivery.provider.pidge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A real driving route (OpenStreetMap, via OSRM) for the seeded Bengaluru pair, so the sandbox rider follows roads
 * instead of the straight pickup-to-drop line (D-192). Two legs: {@link Leg#APPROACH} from where the rider starts to
 * the supplier, {@link Leg#DELIVERY} from the supplier to the outlet. Test-only data, loaded once and validated.
 */
public final class PidgeSandboxRoute {

    public enum Leg { APPROACH, DELIVERY }

    /** A point, 7 decimals (the width of delivery_location). */
    public record Point(BigDecimal latitude, BigDecimal longitude) {
    }

    private static final String RESOURCE = "/pidge-sandbox/route-bengaluru.json";
    /** How far the delivery's stored pickup and drop may sit from the route's supplier and outlet. */
    private static final double MATCH_METRES = 300.0;
    private static final double EARTH_RADIUS_METRES = 6_371_008.8;

    private static volatile PidgeSandboxRoute shared;

    private final double[] supplier;
    private final double[] outlet;
    private final List<double[]> approach;
    private final List<double[]> delivery;
    private final double[] approachCum;
    private final double[] deliveryCum;

    private PidgeSandboxRoute(JsonNode root) {
        this.supplier = pair(root.path("supplier"), "supplier");
        this.outlet = pair(root.path("outlet"), "outlet");
        this.approach = polyline(root.path("approach"), "approach");
        this.delivery = polyline(root.path("delivery"), "delivery");
        this.approachCum = cumulative(approach);
        this.deliveryCum = cumulative(delivery);
        // The legs must join at the supplier and end at the outlet, or the rider would jump.
        require(haversine(approach.get(approach.size() - 1), supplier) < 50, "approach does not end at the supplier");
        require(haversine(delivery.get(0), supplier) < 50, "delivery does not start at the supplier");
        require(haversine(delivery.get(delivery.size() - 1), outlet) < 50, "delivery does not end at the outlet");
    }

    /** The bundled route, loaded and checked on first use. */
    public static PidgeSandboxRoute shared() {
        var local = shared;
        if (local == null) {
            synchronized (PidgeSandboxRoute.class) {
                local = shared;
                if (local == null) {
                    shared = local = load();
                }
            }
        }
        return local;
    }

    private static PidgeSandboxRoute load() {
        try (InputStream in = PidgeSandboxRoute.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return new PidgeSandboxRoute(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True only when the stored pickup and drop are each within 300 m of the route's supplier and outlet. */
    public boolean matches(BigDecimal pickupLat, BigDecimal pickupLng, BigDecimal dropLat, BigDecimal dropLng) {
        if (pickupLat == null || pickupLng == null || dropLat == null || dropLng == null) {
            return false;
        }
        return haversine(new double[] {pickupLat.doubleValue(), pickupLng.doubleValue()}, supplier) <= MATCH_METRES
                && haversine(new double[] {dropLat.doubleValue(), dropLng.doubleValue()}, outlet) <= MATCH_METRES;
    }

    /** The point this fraction (clamped 0..1) of the leg's road length from its start. */
    public Point pointAt(Leg leg, double fraction) {
        double f = Double.isNaN(fraction) ? 0.0 : Math.max(0.0, Math.min(1.0, fraction));
        return atMetres(leg, f * lengthMetres(leg));
    }

    /** The point this many metres of road short of the leg's end (clamped to the leg's start). */
    public Point pointAtMetersFromEnd(Leg leg, double meters) {
        return atMetres(leg, lengthMetres(leg) - Math.max(0.0, meters));
    }

    public double lengthMetres(Leg leg) {
        double[] cum = cumulativeMetres(leg);
        return cum[cum.length - 1];
    }

    /** Distance along the leg at each vertex; starts at 0, strictly increasing. */
    public double[] cumulativeMetres(Leg leg) {
        return (leg == Leg.APPROACH ? approachCum : deliveryCum).clone();
    }

    /** The leg's vertices as {latitude, longitude}. */
    public List<double[]> points(Leg leg) {
        return List.copyOf(leg == Leg.APPROACH ? approach : delivery);
    }

    private Point atMetres(Leg leg, double metres) {
        var pts = leg == Leg.APPROACH ? approach : delivery;
        var cum = leg == Leg.APPROACH ? approachCum : deliveryCum;
        double m = Math.max(0.0, Math.min(cum[cum.length - 1], metres));
        int i = 0;
        while (i < cum.length - 2 && cum[i + 1] < m) {
            i++;
        }
        double seg = cum[i + 1] - cum[i];
        double t = seg <= 0 ? 0.0 : (m - cum[i]) / seg;
        double lat = pts.get(i)[0] + (pts.get(i + 1)[0] - pts.get(i)[0]) * t;
        double lng = pts.get(i)[1] + (pts.get(i + 1)[1] - pts.get(i)[1]) * t;
        return new Point(scale7(lat), scale7(lng));
    }

    private static double[] cumulative(List<double[]> pts) {
        double[] cum = new double[pts.size()];
        for (int i = 1; i < cum.length; i++) {
            cum[i] = cum[i - 1] + haversine(pts.get(i - 1), pts.get(i));
        }
        return cum;
    }

    private static List<double[]> polyline(JsonNode node, String name) {
        require(node.isArray() && node.size() >= 2, name + " needs at least two points");
        var out = new ArrayList<double[]>();
        for (JsonNode p : node) {
            var point = pair(p, name);
            // A repeated vertex adds no road; dropping it keeps the cumulative distance strictly increasing.
            if (out.isEmpty() || haversine(out.get(out.size() - 1), point) > 0) {
                out.add(point);
            }
        }
        require(out.size() >= 2, name + " has no length");
        return out;
    }

    private static double[] pair(JsonNode p, String name) {
        require(p.isArray() && p.size() == 2 && p.get(0).isNumber() && p.get(1).isNumber(), name + " is not [lat,lng]");
        double lat = p.get(0).asDouble();
        double lng = p.get(1).asDouble();
        require(lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180, name + " is out of range");
        return new double[] {lat, lng};
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("Sandbox route: " + message);
        }
    }

    private static double haversine(double[] a, double[] b) {
        double p1 = Math.toRadians(a[0]);
        double p2 = Math.toRadians(b[0]);
        double x = Math.pow(Math.sin((p2 - p1) / 2), 2)
                + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(b[1] - a[1]) / 2), 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.sqrt(x));
    }

    private static BigDecimal scale7(double v) {
        return BigDecimal.valueOf(v).setScale(7, RoundingMode.HALF_UP);
    }
}
