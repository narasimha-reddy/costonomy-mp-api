package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.ProviderReliability;
import com.costonomy.mp.delivery.domain.ProviderReliability.Observations;
import com.costonomy.mp.delivery.domain.ReliabilitySettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How reliable each courier has been lately (D-201 addendum): reads what actually happened to our requests and hands the
 * counts to {@link ProviderReliability}, which scores them and decides the circuit breaker.
 *
 * <p>Recent counts come from a short window of attempts and quotes, across supplier and food delivery together:
 * a courier that stopped sending riders is unreliable for both. The long-term view is the nightly stats. Reading the
 * attempts directly is what lets an outage show within the hour, and it sees the failures the nightly stats cannot,
 * because those only start counting at {@code DRIVER_ASSIGNED}: a quote that failed, a refused booking, and a booking
 * accepted with no rider ever sent.
 *
 * <p>Read straight from the tables with SQL, so it does not reach into another module's repositories. Time is the
 * database's UTC clock: the columns hold UTC, and a Java {@code Timestamp} would read them in this machine's zone.
 */
@Service
@Slf4j
public class ProviderReliabilityService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ReliabilityProperties settings;

    public ProviderReliabilityService(JdbcTemplate jdbc, ReliabilityProperties settings) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.settings = settings;
    }

    public ReliabilitySettings settings() {
        return settings;
    }

    /** One entry per code asked for. A courier with no history is unknown: decent, never skipped. */
    @Transactional(readOnly = true)
    public Map<String, ProviderReliability> assess(Collection<String> providerCodes) {
        return assess(providerCodes, Instant.now());
    }

    Map<String, ProviderReliability> assess(Collection<String> providerCodes, Instant now) {
        var result = new LinkedHashMap<String, ProviderReliability>();
        if (providerCodes.isEmpty()) {
            return result;
        }
        var attempts = recentAttempts(now);
        var quotes = recentQuotes();
        var stats = longTermStats();

        for (String code : providerCodes) {
            var a = attempts.get(code);
            var q = quotes.get(code);
            var st = stats.get(code);
            var observations = new Observations(
                    a == null ? 0 : a[0], a == null ? 0 : a[1], a == null ? 0 : a[2], a == null ? 0 : a[3],
                    a == null ? 0 : a[4], a == null || a[5] < 0 ? null : now.minusSeconds(a[5]),
                    q == null ? 0 : q[0], q == null ? 0 : q[1],
                    st == null ? 0 : st[0], st == null ? 0 : st[1], st == null ? 0 : st[2], st == null ? 0 : st[3],
                    st == null ? 0 : st[4], st == null ? 0 : st[5]);
            result.put(code, ProviderReliability.assess(code, observations, settings, now));
        }
        return result;
    }

    /**
     * Per courier: attempts, refused, settled, assigned, stood down, seconds since the newest attempt.
     *
     * <p>"Settled" is a booked attempt old enough that a missing rider counts against the courier. "Assigned" means a
     * {@code DRIVER_ASSIGNED} event reached the delivery from that courier after the attempt began; "stood down" means
     * the rider then cancelled or the pickup failed. The newest {@code maxSamples} attempts in the window count.
     */
    private Map<String, int[]> recentAttempts(Instant now) {
        String sql = """
                with att as (
                    select provider_code, outcome, started_at, 'S' as flow, delivery_id as did
                      from delivery_provider_attempt
                     where outcome <> 'PENDING' and started_at >= utc_timestamp(6) - interval :window second
                    union all
                    select provider_code, outcome, started_at, 'F', food_delivery_id
                      from food_delivery_attempt
                     where outcome <> 'PENDING' and started_at >= utc_timestamp(6) - interval :window second
                ), ranked as (
                    select att.*, row_number() over (partition by provider_code order by started_at desc) as rn from att
                ), judged as (
                    select r.provider_code, r.started_at, r.flow, r.did,
                           (r.outcome = 'FAILED') as refused,
                           (r.outcome <> 'FAILED'
                              and r.started_at <= utc_timestamp(6) - interval :settle second) as settled,
                           (r.outcome <> 'FAILED'
                              and r.started_at <= utc_timestamp(6) - interval :settle second
                              and (exists (select 1 from delivery_event e
                                            where r.flow = 'S' and e.delivery_id = r.did
                                              and e.provider_code = r.provider_code
                                              and e.status = 'DRIVER_ASSIGNED' and e.disposition = 'APPLIED'
                                              and e.created_at >= r.started_at)
                                or exists (select 1 from food_delivery_event e
                                            where r.flow = 'F' and e.food_delivery_id = r.did
                                              and e.provider_code = r.provider_code
                                              and e.status = 'DRIVER_ASSIGNED' and e.disposition = 'APPLIED'
                                              and e.created_at >= r.started_at))) as assigned
                      from ranked r
                     where r.rn <= :maxSamples
                ), final as (
                    select j.*,
                           (j.assigned
                              and (exists (select 1 from delivery_event e
                                            where j.flow = 'S' and e.delivery_id = j.did
                                              and e.provider_code = j.provider_code
                                              and e.status in ('DRIVER_CANCELLED', 'PICKUP_FAILED')
                                              and e.disposition = 'APPLIED' and e.created_at >= j.started_at)
                                or exists (select 1 from food_delivery_event e
                                            where j.flow = 'F' and e.food_delivery_id = j.did
                                              and e.provider_code = j.provider_code
                                              and e.status in ('DRIVER_CANCELLED', 'PICKUP_FAILED')
                                              and e.disposition = 'APPLIED' and e.created_at >= j.started_at))) as stood_down
                      from judged j
                )
                select provider_code,
                       count(*) as attempts, sum(refused) as refused, sum(settled) as settled,
                       sum(assigned) as assigned, sum(stood_down) as stood_down,
                       timestampdiff(second, max(started_at), utc_timestamp(6)) as quiet_seconds
                  from final
                 group by provider_code
                """;
        var params = Map.<String, Object>of(
                "window", settings.getWindow().toSeconds(),
                "settle", settings.getSettleAfter().toSeconds(),
                "maxSamples", settings.getMaxSamples());
        var out = new HashMap<String, int[]>();
        jdbc.query(sql, params, rs -> {
            out.put(rs.getString("provider_code"), new int[]{
                    rs.getInt("attempts"), rs.getInt("refused"), rs.getInt("settled"),
                    rs.getInt("assigned"), rs.getInt("stood_down"),
                    (int) Math.min(Integer.MAX_VALUE, rs.getLong("quiet_seconds"))});
        });
        return out;
    }

    /**
     * Per courier: quotes asked and quotes that failed. {@code UNSERVICEABLE} is an answer ("we do not go there"),
     * not a failure; a distance-limit quote was never sent to anyone.
     */
    private Map<String, int[]> recentQuotes() {
        String sql = """
                select provider_code, count(*) as asked, sum(status = 'FAILED') as failed
                  from (select provider_code, status from delivery_quote
                         where provider_code <> 'RADIUS_LIMIT'
                           and created_at >= utc_timestamp(6) - interval :window second
                        union all
                        select provider_code, status from food_delivery_quote
                         where created_at >= utc_timestamp(6) - interval :window second) q
                 group by provider_code
                """;
        var out = new HashMap<String, int[]>();
        jdbc.query(sql, Map.of("window", settings.getWindow().toSeconds()),
                rs -> { out.put(rs.getString("provider_code"), new int[]{rs.getInt("asked"), rs.getInt("failed")}); });
        return out;
    }

    /** Per courier over the last {@code statsDays}: bookings, cancellations, pickup failures, delivery failures, completed, late. */
    private Map<String, int[]> longTermStats() {
        String sql = """
                select provider_code,
                       sum(total_bookings) as bookings, sum(driver_cancellations) as cancels,
                       sum(pickup_failures) as pickups, sum(delivery_failures) as dfails,
                       sum(completed_deliveries) as completed, sum(eta_overruns) as late
                  from delivery_provider_stats
                 where window_date >= date_sub(utc_date(), interval :days day)
                 group by provider_code
                """;
        var out = new HashMap<String, int[]>();
        jdbc.query(sql, Map.of("days", settings.getStatsDays()), rs -> {
            out.put(rs.getString("provider_code"), new int[]{
                    rs.getInt("bookings"), rs.getInt("cancels"), rs.getInt("pickups"),
                    rs.getInt("dfails"), rs.getInt("completed"), rs.getInt("late")});
        });
        return out;
    }
}
