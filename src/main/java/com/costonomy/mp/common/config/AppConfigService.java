package com.costonomy.mp.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime configuration from {@code app_config}. Doc 09 §10.
 *
 * <p>Values operations can change without a deploy: acceptance SLA, commission,
 * settlement timing, credit rules, ranking weights, throttling.
 *
 * <p>Reads the highest {@code config_version} that is ACTIVE and already
 * effective. Rows are never updated in place, so a change is an insert — which is
 * what lets a settlement or a recommendation months later be reconstructed with
 * the values that applied at the time (doc 09 §11).
 *
 * <p>Cached in an immutable snapshot swapped atomically, same as
 * {@code RolePermissionCatalog}: a reader sees the old map or the new one, never
 * a half-built one. {@link #refresh()} is called by the operations config
 * endpoint when it lands in Phase 14; until then the snapshot loads once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppConfigService {

    private final JdbcTemplate jdbc;
    private final AtomicReference<Map<String, String>> snapshot = new AtomicReference<>(Map.of());

    public String get(String key, String fallback) {
        Map<String, String> current = snapshot.get();
        if (current.isEmpty()) {
            refresh();
            current = snapshot.get();
        }
        return current.getOrDefault(key, fallback);
    }

    public BigDecimal getDecimal(String key, BigDecimal fallback) {
        String raw = get(key, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            // A misconfigured value must not take the API down. Fall back and be
            // loud about it — the fallback is the same one the code was written
            // against, so behaviour stays sane.
            log.error("Config {} is not a number: {} — using fallback {}", key, raw, fallback);
            return fallback;
        }
    }

    public int getInt(String key, int fallback) {
        return getDecimal(key, BigDecimal.valueOf(fallback)).intValue();
    }

    /** Every key under a prefix, with the prefix stripped. Used to load a weight set. */
    public Map<String, BigDecimal> getDecimalsUnder(String prefix) {
        Map<String, String> current = snapshot.get();
        if (current.isEmpty()) {
            refresh();
            current = snapshot.get();
        }

        Map<String, BigDecimal> values = new HashMap<>();
        current.forEach((key, raw) -> {
            if (key.startsWith(prefix)) {
                try {
                    values.put(key.substring(prefix.length()), new BigDecimal(raw));
                } catch (NumberFormatException ex) {
                    log.error("Config {} is not a number: {} — ignoring", key, raw);
                }
            }
        });
        return values;
    }

    public void refresh() {
        Map<String, String> rebuilt = new HashMap<>();
        jdbc.query("""
                select c.config_key, c.config_value
                  from app_config c
                  join (
                        select config_key, max(config_version) as v
                          from app_config
                         where status = 'ACTIVE'
                           and effective_from <= utc_timestamp(6)
                           and (effective_to is null or effective_to > utc_timestamp(6))
                      group by config_key
                       ) latest
                    on latest.config_key = c.config_key and latest.v = c.config_version
                """,
                rs -> {
                    rebuilt.put(rs.getString(1), rs.getString(2));
                });

        snapshot.set(Map.copyOf(rebuilt));
        log.debug("App config loaded: {} keys", rebuilt.size());
    }
}
