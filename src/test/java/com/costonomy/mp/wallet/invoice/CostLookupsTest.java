package com.costonomy.mp.wallet.invoice;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.ratelimit.InMemoryRateLimiter;
import com.costonomy.mp.common.ratelimit.RateLimiter;
import com.costonomy.mp.wallet.invoice.costapi.CostApiSession;
import com.costonomy.mp.wallet.invoice.costapi.CostCatalog;
import com.costonomy.mp.wallet.invoice.costapi.CostOutletMap;
import com.costonomy.mp.wallet.invoice.costapi.HttpCostCatalog;
import com.costonomy.mp.wallet.invoice.service.InvoiceLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The review pickers (D-114): the cost-app lists over a local stub, and the filtering, cache and limit on our side. */
class CostLookupsTest {

    private static CostAppStub stub;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        stub = new CostAppStub();
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    @BeforeEach
    void reset() {
        stub.reset();
        stub.requireAuth(true);
    }

    private HttpCostCatalog catalog() {
        var session = new CostApiSession(stub.baseUrl(), CostAppStub.USERNAME, CostAppStub.PASSWORD, () -> null,
                Duration.ofSeconds(5), Clock.systemUTC());
        return new HttpCostCatalog(session, "6", Duration.ofSeconds(5));
    }

    /** Marketplace outlets 1 and 2 on cost outlet 77, 3 on 88; 4 has none (D-115). */
    static CostOutletMap outlets(String map, boolean fallback) {
        var env = new MockEnvironment();
        env.setProperty(CostOutletMap.MAP, map);
        env.setProperty(CostOutletMap.FALLBACK, Boolean.toString(fallback));
        env.setProperty(CostOutletMap.READER_OUTLET, "5");
        return new CostOutletMap(env);
    }

    static CostOutletMap outlets() {
        return outlets("1:77,2:77,3:88", false);
    }

    @Test
    @DisplayName("lists: GET only, the configured cost outlet and user, status=false; disabled rows dropped")
    void getsTheConfiguredOutlet() {
        var catalog = catalog();
        assertThat(catalog.suppliers(77)).containsExactly(
                new CostCatalog.SupplierOption(2001L, "Kosta Delights - Sea Food"),
                new CostCatalog.SupplierOption(2002L, "Ganesh Vegetables"));
        var skus = catalog.skus(77);
        assertThat(skus).hasSize(4);
        assertThat(skus.get(0)).isEqualTo(new CostCatalog.SkuOption(9465L, "Prawns 16/20", "KG", new BigDecimal("360"),
                "Seafood"));

        for (var seen : stub.seen()) {
            if (seen.path().startsWith("/auth/")) {
                assertThat(seen.method()).isEqualTo("POST");
                continue;
            }
            assertThat(seen.method()).describedAs(seen.path()).isEqualTo("GET");
            assertThat(seen.path()).isIn("/supplier/list", "/sku/list/expand");
            assertThat(seen.query()).isEqualTo("outlet=77&userId=6&status=false");
            assertThat(seen.body()).isEmpty();
        }
        assertThat(stub.seen("/supplier/list")).hasSize(1);
        assertThat(stub.seen("/sku/list/expand")).hasSize(1);
    }

    @Test
    @DisplayName("D-115: the lists are asked for the cost outlet passed in, and only the two list GETs reach the cost app")
    void onlyTheTwoListCalls() {
        var catalog = catalog();
        catalog.suppliers(88);
        catalog.skus(88);
        for (var seen : stub.seen()) {
            var call = java.util.Arrays.stream(CostApiSession.Call.values())
                    .filter(c -> c.method().equals(seen.method()) && c.path().equals(seen.path())).findFirst();
            assertThat(call).describedAs(seen.method() + " " + seen.path()).isPresent();
            assertThat(call.get()).isIn(CostApiSession.Call.LOGIN, CostApiSession.Call.SUPPLIER_LIST,
                    CostApiSession.Call.SKU_LIST);
            if (!seen.path().startsWith("/auth/")) {
                assertThat(seen.query()).isEqualTo("outlet=88&userId=6&status=false");
            }
        }
    }

    @Test
    @DisplayName("D-115 (M7): building and sending the list requests never logs a token or the password, at any level")
    void secretsNeverLoggedAtTheCallSite() throws Exception {
        var secrets = new java.util.ArrayList<String>();
        try (var logs = new LogCapture()) {
            var catalog = catalog();
            catalog.suppliers(77);
            stub.revokeAccess();                       // 401: refresh and send again
            catalog.skus(77);
            String staticToken = "static-token-for-tests-only-8812";
            var withToken = new HttpCostCatalog(CostApiSession.withStaticToken(stub.baseUrl(), () -> staticToken), "6",
                    Duration.ofSeconds(5));
            stub.requireAuth(false);
            withToken.suppliers(77);
            secrets.addAll(stub.issuedTokens());
            secrets.add(CostAppStub.PASSWORD);
            secrets.add(staticToken);
            assertThat(logs.lines()).isNotEmpty();
            logs.assertNoneContain(secrets);
        }
        assertThat(secrets).hasSizeGreaterThan(3);
    }

    @Test
    @DisplayName("only id and name (and for SKUs unit, price, category) cross over; internal fields never do")
    void onlyAllowedFields() throws Exception {
        var catalog = catalog();
        String out = json.writeValueAsString(List.of(catalog.suppliers(77), catalog.skus(77)));
        for (String forbidden : new String[]{CostAppStub.SKU_MARKER, CostAppStub.SUPPLIER_MARKER,
                CostAppStub.USER_MARKER, CostAppStub.COST_MARKER, "36ABCDE1234F1Z5", "9999999999", "yield", "hsn",
                "createdBy", "phone", "outletId"}) {
            assertThat(out).describedAs("must not contain '%s'", forbidden).doesNotContain(forbidden);
        }
        var sku = json.readTree(json.writeValueAsString(catalog.skus(77).get(0)));
        Set<String> names = new TreeSet<>();
        sku.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "name", "unit", "unitPrice", "categoryName");
    }

    // ── our side: filter, cache, limits ──────────────────────────────────

    private static InvoiceLookupService service(CostCatalog catalog, int perMinute, Clock clock) throws Exception {
        return service(catalog, outlets(), perMinute, clock);
    }

    private static InvoiceLookupService service(CostCatalog catalog, CostOutletMap map, int perMinute, Clock clock)
            throws Exception {
        var factory = new StaticListableBeanFactory();
        factory.addBean("limiter", new InMemoryRateLimiter());
        Constructor<InvoiceLookupService> c = InvoiceLookupService.class.getDeclaredConstructor(CostCatalog.class,
                CostOutletMap.class, org.springframework.beans.factory.ObjectProvider.class, int.class, Clock.class);
        c.setAccessible(true);
        return c.newInstance(catalog, map, factory.getBeanProvider(RateLimiter.class), perMinute, clock);
    }

    /** A catalog that counts how often it is asked. */
    private static class Counting implements CostCatalog {
        final AtomicInteger calls = new AtomicInteger();
        final java.util.List<Long> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public List<SupplierOption> suppliers(long costOutletId) {
            calls.incrementAndGet();
            asked.add(costOutletId);
            return List.of(new SupplierOption(1L, "Kosta Delights - Sea Food"), new SupplierOption(2L, "Delights Bakery"),
                    new SupplierOption(3L, "Ganesh Vegetables"));
        }

        @Override
        public List<SkuOption> skus(long costOutletId) {
            calls.incrementAndGet();
            asked.add(costOutletId);
            return List.of(new SkuOption(1L, "Prawns 16/20", "KG", BigDecimal.TEN, "Seafood"),
                    new SkuOption(2L, "Tiger PRAWNS", "KG", BigDecimal.ONE, "Seafood"),
                    new SkuOption(3L, "Onion", "KG", BigDecimal.ONE, "Veg"));
        }
    }

    @Test
    @DisplayName("q matches inside the name, ignoring case, names starting with it first; limit applies")
    void filtering() throws Exception {
        var svc = service(new Counting(), 0, Clock.systemUTC());
        assertThat(svc.skus(1L, "prawns", null)).extracting(CostCatalog.SkuOption::name)
                .containsExactly("Prawns 16/20", "Tiger PRAWNS");
        assertThat(svc.suppliers(1L, "delights", null)).extracting(CostCatalog.SupplierOption::name)
                .containsExactly("Delights Bakery", "Kosta Delights - Sea Food");
        assertThat(svc.suppliers(1L, null, 1)).hasSize(1);
        assertThat(svc.suppliers(1L, "", null)).hasSize(3);
    }

    @Test
    @DisplayName("q over 60 characters, or a limit outside 1 to 50, is a 400 with a plain sentence")
    void badParameters() throws Exception {
        var svc = service(new Counting(), 0, Clock.systemUTC());
        var e = catchThrowableOfType(() -> svc.suppliers(1L, "x".repeat(61), null), BusinessException.class);
        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(e.details().toString()).contains("at most 60 characters");
        assertThat(catchThrowableOfType(() -> svc.skus(1L, null, 51), BusinessException.class).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(catchThrowableOfType(() -> svc.skus(1L, null, 0), BusinessException.class).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(svc.skus(1L, "x".repeat(60), 50)).isEmpty();
    }

    @Test
    @DisplayName("the lists are kept for 60 seconds per cost outlet: outlets 1 and 2 share cost outlet 77's lists")
    void cache() throws Exception {
        var catalog = new Counting();
        var clock = new CostApiSessionTest.MutableClock(Instant.parse("2026-10-03T05:00:00Z"));
        var svc = service(catalog, 0, clock);
        svc.suppliers(1L, "a", null);
        svc.suppliers(2L, "b", null);
        svc.suppliers(1L, "a", 5);
        assertThat(catalog.calls.get()).isEqualTo(1);
        svc.skus(1L, "a", null);
        assertThat(catalog.calls.get()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(59));
        svc.suppliers(1L, "a", null);
        assertThat(catalog.calls.get()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(2));
        svc.suppliers(1L, "a", null);
        assertThat(catalog.calls.get()).isEqualTo(3);
        svc.suppliers(3L, "a", null);                  // another cost outlet: its own list
        assertThat(catalog.calls.get()).isEqualTo(4);
        assertThat(catalog.asked).containsExactly(77L, 77L, 77L, 88L);
    }

    @Test
    @DisplayName("D-115 (M2): two outlets on different cost outlets never see each other's suppliers or SKUs")
    void outletsNeverSeeEachOthersLists() throws Exception {
        stub.lists("77", "[" + CostAppStub.supplier(2001, "Kosta Delights - Sea Food", false) + "]",
                "[" + CostAppStub.sku(9465, "Prawns 16/20", "360") + "]");
        stub.lists("88", "[" + CostAppStub.supplier(3001, "Other Business Traders", false) + "]",
                "[" + CostAppStub.sku(7001, "Other Business Rice", "55") + "]");
        var svc = service(catalog(), outlets(), 0, Clock.systemUTC());
        assertThat(svc.suppliers(1L, null, null)).extracting(CostCatalog.SupplierOption::id).containsExactly(2001L);
        assertThat(svc.skus(1L, null, null)).extracting(CostCatalog.SkuOption::id).containsExactly(9465L);
        assertThat(svc.suppliers(3L, null, null)).extracting(CostCatalog.SupplierOption::id).containsExactly(3001L);
        assertThat(svc.skus(3L, null, null)).extracting(CostCatalog.SkuOption::id).containsExactly(7001L);
        // and again from the cache, still apart
        assertThat(svc.suppliers(1L, "other", null)).isEmpty();
        assertThat(svc.skus(3L, "prawns", null)).isEmpty();
        assertThat(stub.seen("/supplier/list")).extracting(CostAppStub.Seen::query)
                .containsExactlyInAnyOrder("outlet=77&userId=6&status=false", "outlet=88&userId=6&status=false");
    }

    @Test
    @DisplayName("D-115 (M2): an outlet with no cost outlet gets 403 INVOICE_LOOKUP_NOT_AVAILABLE and nothing is asked")
    void unmappedOutletRefused() throws Exception {
        var catalog = new Counting();
        var svc = service(catalog, outlets(), 0, Clock.systemUTC());
        var e = catchThrowableOfType(() -> svc.suppliers(4L, null, null), BusinessException.class);
        assertThat(e.code()).isEqualTo(ErrorCode.INVOICE_LOOKUP_NOT_AVAILABLE);
        assertThat(e.code().status().value()).isEqualTo(403);
        assertThat(e.getMessage()).isEqualTo(
                "Supplier and SKU lists are not available for this outlet. You can still type a name.");
        assertThat(catchThrowableOfType(() -> svc.skus(4L, null, null), BusinessException.class).code())
                .isEqualTo(ErrorCode.INVOICE_LOOKUP_NOT_AVAILABLE);
        assertThat(catalog.calls.get()).isZero();
        // The fallback applies only when the map is empty and it is on.
        var fallback = service(catalog, outlets("", true), 0, Clock.systemUTC());
        fallback.suppliers(4L, null, null);
        assertThat(catalog.asked).containsExactly(5L);
        var mapWins = service(new Counting(), outlets("1:77", true), 0, Clock.systemUTC());
        assertThat(catchThrowableOfType(() -> mapWins.suppliers(4L, null, null), BusinessException.class).code())
                .isEqualTo(ErrorCode.INVOICE_LOOKUP_NOT_AVAILABLE);
        var off = service(new Counting(), outlets("", false), 0, Clock.systemUTC());
        assertThat(catchThrowableOfType(() -> off.suppliers(1L, null, null), BusinessException.class).code())
                .isEqualTo(ErrorCode.INVOICE_LOOKUP_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("D-115 (L5): a failure is remembered for 10 s; a slow list does not hold up another cost outlet's")
    void failureCachedAndNoGlobalLock() throws Exception {
        var clock = new CostApiSessionTest.MutableClock(Instant.parse("2026-10-03T05:00:00Z"));
        var release = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var catalog = new Counting() {
            @Override
            public List<SupplierOption> suppliers(long costOutletId) {
                if (costOutletId == 77) {
                    entered.countDown();
                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (costOutletId == 88 && failing.get()) {
                    calls.incrementAndGet();
                    throw new com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException("down");
                }
                return super.suppliers(costOutletId);
            }
        };
        var svc = service(catalog, outlets(), 0, clock);
        var slow = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending = slow.submit(() -> svc.suppliers(1L, null, null));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // Cost outlet 77 is still loading; 88 answers (here: fails) at once, not after it.
            long t0 = System.nanoTime();
            var e = catchThrowableOfType(() -> svc.suppliers(3L, null, null), BusinessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
            assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
            release.countDown();
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS)).hasSize(3);
        } finally {
            release.countDown();
            slow.shutdownNow();
        }
        int failedCalls = catalog.calls.get();
        failing.set(false);
        clock.advance(Duration.ofSeconds(9));
        assertThat(catchThrowableOfType(() -> svc.suppliers(3L, null, null), BusinessException.class).code())
                .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(catalog.calls.get()).isEqualTo(failedCalls);   // remembered: the cost app is not asked
        clock.advance(Duration.ofSeconds(2));
        assertThat(svc.suppliers(3L, null, null)).hasSize(3);       // after 10 s it is asked again
    }

    @Test
    @DisplayName("D-115 (K4): a SKU price is kept with at most 4 decimals")
    void skuPriceFourDecimals() {
        stub.lists("77", "[]", "[" + CostAppStub.sku(9465, "Prawns per gram", "0.36125") + "]");
        assertThat(catalog().skus(77).get(0).unitPrice()).isEqualByComparingTo("0.3613");
    }

    @Test
    @DisplayName("a per-outlet limit: the 4th search in a minute from one outlet is a 429, another outlet is not held up")
    void rateLimited() throws Exception {
        var svc = service(new Counting(), 3, Clock.systemUTC());
        for (int i = 0; i < 3; i++) {
            svc.suppliers(1L, null, null);
        }
        var e = catchThrowableOfType(() -> svc.suppliers(1L, null, null), BusinessException.class);
        assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(svc.suppliers(2L, null, null)).isNotEmpty();
    }

    @Test
    @DisplayName("the cost app down is a plain 503, never its answer")
    void unavailable() throws Exception {
        stub.password("not-what-we-send");
        var svc = service(catalog(), 0, Clock.systemUTC());
        var e = catchThrowableOfType(() -> svc.suppliers(1L, null, null), BusinessException.class);
        assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(e.getMessage()).isEqualTo("The cost app's lists are not available right now. You can still type a name.");
    }
}
