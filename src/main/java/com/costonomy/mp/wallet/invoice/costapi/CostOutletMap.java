package com.costonomy.mp.wallet.invoice.costapi;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which cost-app outlet stands for a marketplace outlet (D-115). The cost app's suppliers, SKUs and last purchase
 * prices belong to one business; a marketplace outlet may only see the cost outlet it is mapped to.
 *
 * <ul>
 *   <li>{@code costonomy.mp.invoices.cost-outlet-map}: {@code mpOutletId:costOutletId} pairs, comma separated
 *       ({@code "1:1,7:12"}).</li>
 *   <li>{@code costonomy.mp.invoices.cost-outlet-fallback}: only when it is exactly true and the map is empty, every
 *       marketplace outlet uses {@code costonomy.mp.invoices.reader.outlet}. Off by default, refused in production
 *       ({@code ProductionProviderGuard}).</li>
 * </ul>
 *
 * <p>An outlet without a cost outlet gets no lookups (403 INVOICE_LOOKUP_NOT_AVAILABLE) and its bills are read
 * without the cost app's supplier and SKU matches. The settings are read on each use, so a test can change them.
 */
@Component
public class CostOutletMap {

    public static final String MAP = "costonomy.mp.invoices.cost-outlet-map";
    public static final String FALLBACK = "costonomy.mp.invoices.cost-outlet-fallback";
    public static final String READER_OUTLET = "costonomy.mp.invoices.reader.outlet";

    private final Environment env;
    private volatile Parsed parsed = new Parsed("", Map.of());

    private record Parsed(String raw, Map<Long, Long> map) {
    }

    public CostOutletMap(Environment env) {
        this.env = env;
    }

    /** A map that cannot be read stops the start, naming the setting (never its value). */
    @PostConstruct
    void check() {
        parse(env.getProperty(MAP, ""));
    }

    /** The cost outlet for a marketplace outlet, or empty when it has none. */
    public Optional<Long> costOutletFor(long mpOutletId) {
        Map<Long, Long> map = map();
        if (!map.isEmpty()) {
            return Optional.ofNullable(map.get(mpOutletId));
        }
        if (fallbackOn()) {
            long fallback = readerOutlet();
            return fallback > 0 ? Optional.of(fallback) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The mapping as configured (empty when none). */
    public Map<Long, Long> map() {
        String raw = env.getProperty(MAP, "");
        Parsed p = parsed;
        if (!p.raw().equals(raw)) {
            try {
                p = new Parsed(raw, parse(raw));
            } catch (IllegalStateException e) {
                p = new Parsed(raw, Map.of()); // checked at start; a later bad value maps nothing
            }
            parsed = p;
        }
        return p.map();
    }

    public boolean fallbackOn() {
        String v = env.getProperty(FALLBACK, "false");
        return "true".equalsIgnoreCase(v.strip());
    }

    /** The reading account's own cost outlet; 0 when not set. */
    public long readerOutlet() {
        try {
            return Long.parseLong(env.getProperty(READER_OUTLET, "0").strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static Map<Long, Long> parse(String raw) {
        var out = new LinkedHashMap<Long, Long>();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        for (String pair : raw.split(",")) {
            String p = pair.strip();
            if (p.isEmpty()) {
                continue;
            }
            String[] parts = p.split(":");
            long mp;
            long cost;
            try {
                if (parts.length != 2) {
                    throw new NumberFormatException();
                }
                mp = Long.parseLong(parts[0].strip());
                cost = Long.parseLong(parts[1].strip());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(MAP + " must be mpOutletId:costOutletId pairs separated by commas.");
            }
            if (mp <= 0 || cost <= 0) {
                throw new IllegalStateException(MAP + " must use outlet ids above 0.");
            }
            if (out.put(mp, cost) != null) {
                throw new IllegalStateException(MAP + " names a marketplace outlet more than once.");
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
