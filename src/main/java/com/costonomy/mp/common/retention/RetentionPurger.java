package com.costonomy.mp.common.retention;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deletes rows that only ever grew (D-182, docs/performance/DB_BOTTLENECKS.md).
 *
 * <p>Each rule deletes in small batches, each batch its own statement and commit: a single large DELETE holds locks,
 * bloats the undo log and lags replicas, which is what this exists to avoid. There is deliberately <b>no transaction
 * around the run</b>. A run is bounded ({@link #MAX_BATCHES_PER_RULE}) so a huge backlog is worked off over several
 * nights rather than in one.
 *
 * <p>What is not here, on purpose: {@code audit_log}, {@code payment_webhook_event}, payments, refunds, wallet and order
 * tables (financial or legal records, a retention decision for their owners), {@code notification} (a user's inbox) and
 * FAILED outbox rows (terminal and visible so someone sees them).
 */
@Component
@Slf4j
public class RetentionPurger {

    /** One run removes at most this many batches per table. */
    static final int MAX_BATCHES_PER_RULE = 200;

    private final JdbcTemplate jdbc;

    @Value("${costonomy.mp.retention.batch-size:5000}")
    private int batchSize;
    @Value("${costonomy.mp.retention.outbox-published-days:14}")
    private int outboxPublishedDays;
    @Value("${costonomy.mp.retention.delivery-location-days:30}")
    private int deliveryLocationDays;
    @Value("${costonomy.mp.retention.refresh-token-days:30}")
    private int refreshTokenDays;
    @Value("${costonomy.mp.retention.otp-days:7}")
    private int otpDays;

    public RetentionPurger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A rule: what is deleted and the cut-off it is deleted before. */
    private record Rule(String name, String sql, Instant before) {
    }

    private List<Rule> rules(Instant now) {
        var rules = new ArrayList<Rule>();
        // The idempotency window (24 h) has passed: a key this old cannot be replayed. The purge existed
        // (IdempotencyStore.deleteExpired) and was never called.
        rules.add(new Rule("idempotency_record",
                "delete from idempotency_record where expires_at < ? limit ?", now));
        // Published events were delivered; FAILED ones are kept for a person to see.
        rules.add(new Rule("outbox_event",
                "delete from outbox_event where status = 'PUBLISHED' and published_at < ? limit ?",
                now.minus(Duration.ofDays(outboxPublishedDays))));
        // Live position matters while a delivery is in flight, not a month later.
        rules.add(new Rule("delivery_location",
                "delete from delivery_location where recorded_at < ? limit ?",
                now.minus(Duration.ofDays(deliveryLocationDays))));
        rules.add(new Rule("refresh_token",
                "delete from refresh_token where expires_at < ? limit ?",
                now.minus(Duration.ofDays(refreshTokenDays))));
        rules.add(new Rule("otp_verification",
                "delete from otp_verification where expires_at < ? limit ?",
                now.minus(Duration.ofDays(otpDays))));
        return rules;
    }

    /** Run every rule. Returns rows deleted per table. */
    public Map<String, Long> runAll(Instant now) {
        return runAll(now, batchSize);
    }

    Map<String, Long> runAll(Instant now, int batch) {
        var deleted = new LinkedHashMap<String, Long>();
        for (Rule rule : rules(now)) {
            long total = 0;
            try {
                for (int i = 0; i < MAX_BATCHES_PER_RULE; i++) {
                    int removed = jdbc.update(rule.sql(), java.sql.Timestamp.from(rule.before()), batch);
                    total += removed;
                    if (removed < batch) {
                        break;
                    }
                }
            } catch (RuntimeException ex) {
                // One table failing must not stop the others; it is tried again tomorrow.
                log.warn("Retention purge of {} failed after {} rows: {}", rule.name(), total, ex.toString());
            }
            deleted.put(rule.name(), total);
        }
        return deleted;
    }
}
