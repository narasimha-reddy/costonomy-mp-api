package com.costonomy.mp.wallet.invoice.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.ratelimit.RateLimitPolicy;
import com.costonomy.mp.common.ratelimit.RateLimiter;
import com.costonomy.mp.wallet.invoice.costapi.CostCatalog;
import com.costonomy.mp.wallet.invoice.costapi.CostOutletMap;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The review screen's supplier and SKU pickers (D-114), read from the cost app through {@link CostCatalog}. The
 * cost app has no search: it lists everything for its outlet, so the whole list is kept for 60 seconds and filtered
 * here. A per-outlet limit keeps one busy screen from turning into a stream of calls to the cost app.
 *
 * <p>D-115: the lists are those of the cost outlet the caller's outlet is mapped to ({@link CostOutletMap}) and are
 * cached per cost outlet, so two outlets mapped to different cost outlets never see each other's lists. An outlet
 * without one is refused (403 INVOICE_LOOKUP_NOT_AVAILABLE) before the cost app is asked. Loading happens under a
 * lock per list, never one lock for all, and a failure is remembered for 10 seconds so an outage does not queue every
 * search behind a slow call.
 */
@Service
@Slf4j
public class InvoiceLookupService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_QUERY = 60;
    static final Duration CACHE_FOR = Duration.ofSeconds(60);
    static final Duration FAILURE_CACHED_FOR = Duration.ofSeconds(10);
    static final String UNAVAILABLE = "The cost app's lists are not available right now. You can still type a name.";

    private final CostCatalog catalog;
    private final CostOutletMap outletMap;
    private final ObjectProvider<RateLimiter> limiter;
    private final RateLimitPolicy policy;
    private final Clock clock;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> loading = new ConcurrentHashMap<>();

    /** A list, or a failure ({@code rows == null}), good until {@code until}. */
    private record Cached<T>(List<T> rows, Instant until) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InvoiceLookupService(CostCatalog catalog, CostOutletMap outletMap, ObjectProvider<RateLimiter> limiter,
                                @Value("${costonomy.mp.invoices.lookups.per-minute:60}") int perMinute) {
        this(catalog, outletMap, limiter, perMinute, Clock.systemUTC());
    }

    InvoiceLookupService(CostCatalog catalog, CostOutletMap outletMap, ObjectProvider<RateLimiter> limiter,
                         int perMinute, Clock clock) {
        this.catalog = catalog;
        this.outletMap = outletMap;
        this.limiter = limiter;
        this.policy = new RateLimitPolicy("invoice-lookups", perMinute, Duration.ofMinutes(1),
                RateLimitPolicy.KeyBy.USER);
        this.clock = clock;
    }

    public List<CostCatalog.SupplierOption> suppliers(Long outletId, String q, Integer limit) {
        String query = query(q);
        int n = limit(limit);
        long cost = costOutlet(outletId);
        throttle(outletId);
        return filter(cached("suppliers:" + cost, () -> catalog.suppliers(cost)), CostCatalog.SupplierOption::name,
                query, n);
    }

    /** The cost app's SKU list carries no supplier, so there is no supplier filter (D-115). */
    public List<CostCatalog.SkuOption> skus(Long outletId, String q, Integer limit) {
        String query = query(q);
        int n = limit(limit);
        long cost = costOutlet(outletId);
        throttle(outletId);
        return filter(cached("skus:" + cost, () -> catalog.skus(cost)), CostCatalog.SkuOption::name, query, n);
    }

    private long costOutlet(Long outletId) {
        return outletMap.costOutletFor(outletId).orElseThrow(
                () -> new BusinessException(ErrorCode.INVOICE_LOOKUP_NOT_AVAILABLE));
    }

    private static String query(String q) {
        String t = q == null ? "" : q.strip();
        if (t.length() > MAX_QUERY) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    Map.of("fields", Map.of("q", "Search with at most " + MAX_QUERY + " characters.")));
        }
        return t.toLowerCase(Locale.ROOT);
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    Map.of("fields", Map.of("limit", "Ask for 1 to " + MAX_LIMIT + " results.")));
        }
        return limit;
    }

    private void throttle(Long outletId) {
        if (policy.unlimited()) {
            return;
        }
        RateLimiter rl = limiter.getIfAvailable();
        if (rl == null) {
            return;
        }
        var decision = rl.tryAcquire("invoice-lookups:outlet:" + outletId, policy);
        if (!decision.allowed()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many searches for this outlet. Please wait a moment.",
                    Map.of("retryAfterSeconds", decision.retryAfterSeconds()));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> cached(String key, Supplier<List<T>> load) {
        Cached<?> hit = fresh(key);
        if (hit == null) {
            // One load per list at a time; other lists (other cost outlets) are not held up by it.
            synchronized (loading.computeIfAbsent(key, k -> new Object())) {
                hit = fresh(key);
                if (hit == null) {
                    try {
                        hit = new Cached<>(List.copyOf(load.get()), clock.instant().plus(CACHE_FOR));
                    } catch (InvoiceReadException e) {
                        log.warn("Cost-app {} lookup failed: {}", key, e.getMessage());
                        hit = new Cached<>(null, clock.instant().plus(FAILURE_CACHED_FOR));
                    }
                    cache.put(key, hit);
                }
            }
        }
        if (hit.rows() == null) {
            throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE, UNAVAILABLE);
        }
        return (List<T>) hit.rows();
    }

    private Cached<?> fresh(String key) {
        Cached<?> hit = cache.get(key);
        return hit != null && clock.instant().isBefore(hit.until()) ? hit : null;
    }

    /** Names containing the query, those starting with it first, then by name. */
    private static <T> List<T> filter(List<T> rows, Function<T, String> name, String query, int limit) {
        return rows.stream()
                .filter(r -> query.isEmpty() || name.apply(r).toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.<T, Boolean>comparing(r -> !name.apply(r).toLowerCase(Locale.ROOT).startsWith(query))
                        .thenComparing(r -> name.apply(r).toLowerCase(Locale.ROOT)))
                .limit(limit)
                .toList();
    }
}
