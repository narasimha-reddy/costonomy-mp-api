package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.delivery.domain.ProviderReliability.BreakerState;
import com.costonomy.mp.delivery.service.ProviderReliabilityService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reliability SQL against the real schema: what it counts from recent attempts, quotes and events in both flows,
 * and that a courier with no history is simply unknown. Rows are seeded under unique courier codes with foreign keys
 * off for the seeding connection only, since the point is the query and not the delivery that owns the rows.
 */
class ProviderReliabilityServiceIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProviderReliabilityService service;

    private static String code(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Runs statements on one connection with foreign keys off, then restores them. */
    private void seed(List<String> statements) {
        jdbc.execute((java.sql.Connection con) -> {
            try (var st = con.createStatement()) {
                st.execute("set foreign_key_checks = 0");
                try {
                    for (String sql : statements) {
                        st.execute(sql);
                    }
                } finally {
                    st.execute("set foreign_key_checks = 1");
                }
            }
            return null;
        });
    }

    /** A food attempt that began {@code minutesAgo}, in the given outcome. */
    private static String foodAttempt(String provider, long delivery, int n, String outcome, int minutesAgo) {
        return "insert into food_delivery_attempt (food_delivery_id, provider_code, attempt_number, attempt_type, outcome, started_at) "
                + "values (%d, '%s', %d, 'BOOKING', '%s', utc_timestamp(6) - interval %d minute)"
                .formatted(delivery, provider, n, outcome, minutesAgo);
    }

    private static String supplierAttempt(String provider, long delivery, int n, String outcome, int minutesAgo) {
        return "insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome, started_at) "
                + "values (%d, '%s', %d, 'BOOKING', '%s', utc_timestamp(6) - interval %d minute)"
                .formatted(delivery, provider, n, outcome, minutesAgo);
    }

    private static String foodEvent(String provider, long delivery, String status, int minutesAgo) {
        return "insert into food_delivery_event (food_delivery_id, event_type, status, provider_code, disposition, occurred_at, created_at) "
                + "values (%d, 'e', '%s', '%s', 'APPLIED', utc_timestamp(6), utc_timestamp(6) - interval %d minute)"
                .formatted(delivery, status, provider, minutesAgo);
    }

    private static String supplierEvent(String provider, long delivery, String status, int minutesAgo) {
        return "insert into delivery_event (delivery_id, event_type, status, provider_code, disposition, occurred_at, created_at) "
                + "values (%d, 'e', '%s', '%s', 'APPLIED', utc_timestamp(6), utc_timestamp(6) - interval %d minute)"
                .formatted(delivery, status, provider, minutesAgo);
    }

    @Test
    @DisplayName("a courier that accepts bookings but sends no riders opens the breaker; one that delivers stays closed")
    void failingAndHealthyCouriers() {
        String bad = code("BAD");
        String good = code("GOOD");
        long base = 900_000_000L + (System.nanoTime() % 1_000_000L) * 10;
        var rows = new java.util.ArrayList<String>();

        // BAD: across both flows, 8 attempts 7 minutes ago: past the 5-minute settle window, short of the 10-minute probe. Two refused outright, six booked with no rider ever sent.
        for (int i = 0; i < 4; i++) {
            rows.add(foodAttempt(bad, base + i, 1, i < 1 ? "FAILED" : "BOOKED", 7));
            rows.add(supplierAttempt(bad, base + 100 + i, 1, i < 1 ? "FAILED" : "BOOKED", 7));
        }
        // GOOD: 6 attempts, all assigned a rider who stayed.
        for (int i = 0; i < 3; i++) {
            rows.add(foodAttempt(good, base + 200 + i, 1, "BOOKED", 8));
            rows.add(foodEvent(good, base + 200 + i, "DRIVER_ASSIGNED", 6));
            rows.add(supplierAttempt(good, base + 300 + i, 1, "BOOKED", 8));
            rows.add(supplierEvent(good, base + 300 + i, "DRIVER_ASSIGNED", 6));
        }
        seed(rows);

        var result = service.assess(List.of(bad, good, code("NEW")));

        var b = result.get(bad);
        assertThat(b.judged()).as("2 refused + 6 settled").isEqualTo(8);
        assertThat(b.fulfilment()).isZero();
        assertThat(b.breaker()).isEqualTo(BreakerState.OPEN);

        var g = result.get(good);
        assertThat(g.judged()).isEqualTo(6);
        assertThat(g.fulfilment()).isEqualTo(1.0);
        assertThat(g.breaker()).isEqualTo(BreakerState.CLOSED);
        assertThat(g.score()).isGreaterThan(b.score());

        var unknown = result.get(result.keySet().stream().filter(k -> k.startsWith("NEW_")).findFirst().orElseThrow());
        assertThat(unknown.breaker()).isEqualTo(BreakerState.CLOSED);
        assertThat(unknown.judged()).isZero();
    }

    @Test
    @DisplayName("a rider who was assigned and then cancelled is not counted as fulfilled")
    void standDownIsCounted() {
        String p = code("CANCELS");
        long base = 910_000_000L + (System.nanoTime() % 1_000_000L) * 10;
        var rows = new java.util.ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            rows.add(foodAttempt(p, base + i, 1, "BOOKED", 7));
            rows.add(foodEvent(p, base + i, "DRIVER_ASSIGNED", 6));
            if (i < 3) {
                rows.add(foodEvent(p, base + i, "DRIVER_CANCELLED", 5));
            }
        }
        seed(rows);

        var r = service.assess(List.of(p)).get(p);

        assertThat(r.judged()).isEqualTo(5);
        assertThat(r.fulfilment()).as("5 assigned, 3 of them then cancelled").isEqualTo(0.4);
        assertThat(r.breaker()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    @DisplayName("an attempt still inside the settle window is not held against the courier")
    void freshAttemptsAreNotJudged() {
        String p = code("FRESH");
        long base = 920_000_000L + (System.nanoTime() % 1_000_000L) * 10;
        var rows = new java.util.ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            rows.add(foodAttempt(p, base + i, 1, "BOOKED", 1)); // one minute ago: a rider may still be on the way
        }
        seed(rows);

        var r = service.assess(List.of(p)).get(p);

        assertThat(r.judged()).isZero();
        assertThat(r.breaker()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    @DisplayName("attempts older than the window do not count, so an old outage is forgotten")
    void oldAttemptsAgeOut() {
        String p = code("OLD");
        long base = 930_000_000L + (System.nanoTime() % 1_000_000L) * 10;
        var rows = new java.util.ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            rows.add(foodAttempt(p, base + i, 1, "FAILED", 60 * 5)); // five hours ago, window is two
        }
        seed(rows);

        var r = service.assess(List.of(p)).get(p);

        assertThat(r.judged()).isZero();
        assertThat(r.breaker()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    @DisplayName("after a quiet spell an open breaker lets a probe through")
    void probeAfterQuiet() {
        String p = code("QUIET");
        long base = 940_000_000L + (System.nanoTime() % 1_000_000L) * 10;
        var rows = new java.util.ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            rows.add(foodAttempt(p, base + i, 1, "FAILED", 30)); // all refused, the newest 30 minutes ago
        }
        seed(rows);

        var r = service.assess(List.of(p)).get(p);

        assertThat(r.fulfilment()).isZero();
        assertThat(r.breaker()).as("last tried 30 minutes ago, probe after 10").isEqualTo(BreakerState.HALF_OPEN);
    }

    @Test
    @DisplayName("failed quotes lower the score; an 'unserviceable' answer and a distance-limit quote do not")
    void quoteFailuresCount() {
        String flaky = code("FLAKY");
        String picky = code("PICKY");
        long base = 950_000_000L + (System.nanoTime() % 1_000_000L) * 10;
        var rows = new java.util.ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            rows.add("insert into food_delivery_quote (food_delivery_id, provider_code, status) values (%d, '%s', '%s')"
                    .formatted(base + i, flaky, i < 6 ? "FAILED" : "QUOTED"));
            rows.add("insert into food_delivery_quote (food_delivery_id, provider_code, status) values (%d, '%s', 'UNSERVICEABLE')"
                    .formatted(base + 50 + i, picky));
        }
        rows.add("insert into delivery_quote (delivery_id, provider_code, status) values (%d, 'RADIUS_LIMIT', 'UNSERVICEABLE')".formatted(base));
        seed(rows);

        var result = service.assess(List.of(flaky, picky));

        assertThat(result.get(flaky).score()).isLessThan(result.get(picky).score());
    }

    @Test
    @DisplayName("the long-term nightly stats feed the score")
    void nightlyStatsCount() {
        String cancels = code("STATS");
        seed(List.of("insert into delivery_provider_stats (provider_code, window_date, total_bookings, driver_cancellations, "
                + "pickup_failures, delivery_failures, eta_overruns, completed_deliveries) "
                + "values ('%s', utc_date() - interval 1 day, 100, 40, 10, 0, 0, 50)".formatted(cancels)));

        double withStats = service.assess(List.of(cancels)).get(cancels).score();
        double unknown = service.assess(List.of(code("NOSTATS"))).values().iterator().next().score();

        assertThat(withStats).isLessThan(unknown);
    }
}
