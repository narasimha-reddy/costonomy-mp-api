package com.costonomy.mp.delivery.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps every delivery that cost courier money in line with its payable, for every source (D-206).
 *
 * <p><b>A durable cursor, not a lookback.</b> Each source's deliveries are visited in {@code (updated_at, id)} order behind a
 * cursor stored in {@code courier_payable_sync_cursor}. A delivery that changes moves to the end of that order, so every
 * change after the cursor is visited, oldest first; one already visited is not looked at again, so synced rows can never
 * hold back older eligible ones; and there is no window, so a delivery older than any window is still reached (the first
 * pass walks the whole history, a batch at a time). The previous scan took the newest 500 touched in 48 hours on every pass.
 *
 * <p><b>Nothing is silently skipped.</b> A delivery that fails to sync stops the batch at that delivery and is tried again
 * next pass; only after {@value #GIVE_UP_AFTER} passes in a row does the cursor move past it, loudly, and it is listed in
 * {@link Health#skipped()} for a backfill. A separate audit counts eligible bookings with no payable (the one failure the
 * cursor cannot see), exposes it as a gauge and as an error in the log, and {@link #backfill} repairs it page by page.
 */
@Service
@Slf4j
public class CourierPayableSyncService {

    static final int GIVE_UP_AFTER = 3;

    private final List<CourierPayableSyncSource> sources;
    private final JdbcTemplate jdbc;
    private final CourierPayableProperties props;

    private final Map<String, AtomicLong> missing = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> oldestMissingSeconds = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cursorLagSeconds = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> skipped = new ConcurrentHashMap<>();
    private volatile Instant auditedAt;

    public CourierPayableSyncService(List<CourierPayableSyncSource> sources, JdbcTemplate jdbc,
                                     CourierPayableProperties props, ObjectProvider<MeterRegistry> registry) {
        this.sources = sources;
        this.jdbc = jdbc;
        this.props = props;
        var meters = registry.getIfAvailable();
        for (var source : sources) {
            var type = source.sourceType();
            missing.put(type, new AtomicLong());
            oldestMissingSeconds.put(type, new AtomicLong());
            cursorLagSeconds.put(type, new AtomicLong());
            if (meters != null) {
                Gauge.builder("courier.payable.sync.missing", missing.get(type), AtomicLong::get).tag("source", type)
                        .description("Courier bookings with no payable").register(meters);
                Gauge.builder("courier.payable.sync.missing.oldest.seconds", oldestMissingSeconds.get(type),
                        AtomicLong::get).tag("source", type).register(meters);
                Gauge.builder("courier.payable.sync.cursor.lag.seconds", cursorLagSeconds.get(type), AtomicLong::get)
                        .tag("source", type).description("How far behind the newest change the sync cursor is").register(meters);
            }
        }
    }

    /** What one pass did. */
    public record Pass(int visited, int failed, int skipped) {
    }

    /** What the audit last saw, for the finance queue. */
    public record Health(long missingPayables, long oldestMissingSeconds, long cursorLagSeconds, List<String> skipped,
                         Instant auditedAt) {
    }

    /** A page of the backfill: what it did and where to ask for the next page. */
    public record Backfill(int processed, int failed, long nextAfterId, long remaining) {
    }

    // ── the cursor pass ──────────────────────────────────────────────────

    /** One pass over every source; bounded by {@code syncMaxBatchesPerPass} batches each. */
    public Pass run() {
        int visited = 0;
        int failed = 0;
        int skippedNow = 0;
        for (var source : sources) {
            try {
                var p = runSource(source);
                visited += p.visited();
                failed += p.failed();
                skippedNow += p.skipped();
            } catch (RuntimeException ex) {
                log.error("Courier payable sync failed for {}", source.sourceType(), ex);
            }
        }
        return new Pass(visited, failed, skippedNow);
    }

    private Pass runSource(CourierPayableSyncSource source) {
        String type = source.sourceType();
        int visited = 0;
        int failed = 0;
        int skippedNow = 0;
        int lag = (int) props.getSyncSettleLag().toSeconds();
        for (int batch = 0; batch < props.getSyncMaxBatchesPerPass(); batch++) {
            var cursor = readCursor(type);
            var candidates = source.candidatesAfter(cursor.updatedAt(), cursor.id(), lag, props.getSyncBatch());
            if (candidates.isEmpty()) {
                break;
            }
            CourierPayableSyncSource.Candidate last = null;
            boolean blocked = false;
            for (var c : candidates) {
                try {
                    source.syncOne(c.id());
                    consecutiveFailures.remove(type + ":" + c.id());
                    visited++;
                } catch (RuntimeException ex) {
                    failed++;
                    int times = consecutiveFailures.merge(type + ":" + c.id(), 1, Integer::sum);
                    if (times < GIVE_UP_AFTER) {
                        log.warn("Courier payable sync of {} {} failed ({} of {}); will retry: {}", type, c.id(), times,
                                GIVE_UP_AFTER, ex.getMessage());
                        blocked = true;       // stop here: the cursor stays before it, so it is tried again first
                        break;
                    }
                    log.error("ALERT: courier payable sync of {} {} failed {} passes in a row; skipping it. Fix the cause, "
                            + "then run the backfill or rewind the cursor.", type, c.id(), times, ex);
                    skipped.computeIfAbsent(type, k -> new ArrayList<>()).add(c.id());
                    consecutiveFailures.remove(type + ":" + c.id());
                    skippedNow++;
                }
                last = c;
            }
            if (last != null) {
                writeCursor(type, last.rawUpdatedAt(), last.id());
            }
            if (blocked || candidates.size() < props.getSyncBatch()) {
                break;
            }
        }
        return new Pass(visited, failed, skippedNow);
    }

    private record Cursor(Timestamp updatedAt, long id) {
    }

    private Cursor readCursor(String type) {
        var rows = jdbc.query("select last_updated_at, last_id from courier_payable_sync_cursor where source_type = ?",
                (rs, i) -> new Cursor(rs.getTimestamp(1), rs.getLong(2)), type);
        if (rows.isEmpty()) {
            jdbc.update("insert ignore into courier_payable_sync_cursor (source_type) values (?)", type);
            rows = jdbc.query("select last_updated_at, last_id from courier_payable_sync_cursor where source_type = ?",
                    (rs, i) -> new Cursor(rs.getTimestamp(1), rs.getLong(2)), type);
        }
        return rows.get(0);
    }

    private void writeCursor(String type, Timestamp updatedAt, long id) {
        jdbc.update("update courier_payable_sync_cursor set last_updated_at = ?, last_id = ?, "
                + "last_run_at = utc_timestamp(6), version = version + 1 where source_type = ?", updatedAt, id, type);
    }

    /** Start the walk again from the beginning: every delivery is visited once more. Syncing is idempotent. */
    public void rewind(String sourceType) {
        requireSource(sourceType);
        jdbc.update("update courier_payable_sync_cursor set last_updated_at = '1970-01-02 00:00:00', last_id = 0, "
                + "version = version + 1 where source_type = ?", sourceType);
        skipped.remove(sourceType);
    }

    // ── repair ───────────────────────────────────────────────────────────

    /**
     * Create the payables of deliveries that have a courier booking and none, one deterministic page at a time (by delivery
     * id, after {@code afterId}). Safe to repeat and to run beside the job. The caller asks for the next page with
     * {@link Backfill#nextAfterId()} until {@link Backfill#remaining()} is zero.
     */
    public Backfill backfill(String sourceType, long afterId, int limit) {
        var source = requireSource(sourceType);
        int size = Math.max(1, Math.min(limit, 500));
        var ids = source.idsEligibleWithoutPayable(afterId, size);
        int done = 0;
        int failed = 0;
        long next = afterId;
        for (Long id : ids) {
            try {
                source.syncOne(id);
                done++;
            } catch (RuntimeException ex) {
                failed++;
                log.error("Backfill of courier payable for {} {} failed: {}", sourceType, id, ex.getMessage());
            }
            next = id;
        }
        return new Backfill(done, failed, next, source.countEligibleWithoutPayable());
    }

    private CourierPayableSyncSource requireSource(String sourceType) {
        return sources.stream().filter(s -> s.sourceType().equals(sourceType)).findFirst()
                .orElseThrow(() -> new com.costonomy.mp.common.error.BusinessException(
                        com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR,
                        "Unknown source " + sourceType + "; use SUPPLIER_DELIVERY or FOOD_DELIVERY."));
    }

    // ── the audit ────────────────────────────────────────────────────────

    /** Count what the cursor cannot see: bookings with no payable, and how far behind the cursor is. */
    public Health audit() {
        long totalMissing = 0;
        long oldest = 0;
        long lag = 0;
        for (var source : sources) {
            String type = source.sourceType();
            try {
                long n = source.countEligibleWithoutPayable();
                long age = n == 0 ? 0 : source.oldestEligibleWithoutPayableSeconds();
                Long behind = jdbc.queryForObject("select coalesce(timestampdiff(second, last_updated_at, utc_timestamp(6)), 0) "
                        + "from courier_payable_sync_cursor where source_type = ?", Long.class, type);
                missing.get(type).set(n);
                oldestMissingSeconds.get(type).set(age);
                cursorLagSeconds.get(type).set(behind == null ? 0 : behind);
                totalMissing += n;
                oldest = Math.max(oldest, age);
                lag = Math.max(lag, behind == null ? 0 : behind);
                if (n > 0 && age >= props.getMissingAlertAfter().toSeconds()) {
                    log.error("ALERT: {} {} deliveries have a courier booking and no payable (the oldest for {} s). "
                            + "Run POST /admin/delivery-finance/payables/backfill.", n, type, age);
                } else if (n > 0) {
                    log.warn("{} {} deliveries have a courier booking and no payable yet", n, type);
                }
            } catch (RuntimeException ex) {
                log.error("Courier payable audit failed for {}", type, ex);
            }
        }
        auditedAt = Instant.now();
        return health(totalMissing, oldest, lag);
    }

    /** The last audit, or a fresh one if there has been none in the last minute. */
    public Health health() {
        var at = auditedAt;
        if (at == null || at.isBefore(Instant.now().minusSeconds(60))) {
            return audit();
        }
        return health(missing.values().stream().mapToLong(AtomicLong::get).sum(),
                oldestMissingSeconds.values().stream().mapToLong(AtomicLong::get).max().orElse(0),
                cursorLagSeconds.values().stream().mapToLong(AtomicLong::get).max().orElse(0));
    }

    private Health health(long totalMissing, long oldest, long lag) {
        var skippedNow = new ArrayList<String>();
        skipped.forEach((type, ids) -> ids.forEach(id -> skippedNow.add(type + ":" + id)));
        return new Health(totalMissing, oldest, lag, skippedNow, auditedAt);
    }
}
