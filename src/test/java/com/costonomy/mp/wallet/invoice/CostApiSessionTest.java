package com.costonomy.mp.wallet.invoice;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.costonomy.mp.wallet.invoice.costapi.CostApiSession;
import com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException;
import com.costonomy.mp.wallet.invoice.reader.HttpInvoiceReader;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReader.InvoiceFile;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReader.ReadContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cost-app service sign-in (D-114) against a local stub. No real service is ever called. */
class CostApiSessionTest {

    private static CostAppStub stub;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T05:00:00Z"));
    private ListAppender<ILoggingEvent> logs;
    private Logger ours;
    private Level oursBefore;

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
        // Capture everything our code logs, at every level, so a token logged even at TRACE is caught.
        ours = (Logger) LoggerFactory.getLogger("com.costonomy");
        oursBefore = ours.getLevel();
        ours.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        ours.addAppender(logs);
    }

    @AfterEach
    void detach() {
        ours.detachAppender(logs);
        ours.setLevel(oursBefore);
    }

    private CostApiSession session(String password, Duration authTimeout) {
        return new CostApiSession(stub.baseUrl(), CostAppStub.USERNAME, password, () -> null, authTimeout, clock);
    }

    private CostApiSession session() {
        return session(CostAppStub.PASSWORD, Duration.ofSeconds(5));
    }

    /** One of the allowed calls, named by its path, as the reader and the lookups make them. */
    private static int get(CostApiSession session, String path) throws Exception {
        var call = java.util.Arrays.stream(CostApiSession.Call.values()).filter(c -> c.path().equals(path))
                .findFirst().orElseThrow();
        var body = "POST".equals(call.method()) ? new CostApiSession.Body("application/octet-stream", new byte[]{1})
                : null;
        return session.call(call, java.util.Map.of("outlet", "77"), body, Duration.ofSeconds(5)).statusCode();
    }

    private static List<InvoiceFile> pages() {
        return List.of(new InvoiceFile("page-1.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}));
    }

    @Test
    @DisplayName("login then extract: one sign-in with username, password and provider local; the token is sent as a bearer")
    void loginAndExtract() throws Exception {
        var reader = new HttpInvoiceReader(session(), "77", "6", Duration.ofSeconds(5));

        var reading = reader.read(pages(), new ReadContext(1, 2)).reading();

        assertThat(reading.vendorName()).isEqualTo("KOSTA Delights");
        var login = stub.seen("/auth/login");
        assertThat(login).hasSize(1);
        assertThat(login.get(0).method()).isEqualTo("POST");
        assertThat(login.get(0).contentType()).startsWith("application/json");
        assertThat(login.get(0).bodyText()).contains("\"username\":\"" + CostAppStub.USERNAME + "\"")
                .contains("\"provider\":\"local\"");
        var extract = stub.seen("/item-purchase/invoice/extract");
        assertThat(extract).hasSize(1);
        assertThat(extract.get(0).query()).isEqualTo("pageName=WalletBill&outlet=77&userId=6");
        assertThat(stub.issuedTokens()).contains(extract.get(0).authorization().substring(7));

        // A second read reuses the token: no new sign-in.
        reader.read(pages(), new ReadContext(1, 3));
        assertThat(stub.logins()).isEqualTo(1);
        assertThat(stub.refreshes()).isZero();
    }

    @Test
    @DisplayName("the token is renewed with the refresh token 60 s before it expires, not later and not by a new sign-in")
    void refreshBeforeExpiry() throws Exception {
        var session = session();
        assertThat(get(session, "/supplier/list")).isEqualTo(200);
        String first = stub.seen("/supplier/list").get(0).authorization();

        clock.advance(Duration.ofSeconds(900 - 61)); // still more than 60 s left
        assertThat(get(session, "/supplier/list")).isEqualTo(200);
        assertThat(stub.refreshes()).isZero();

        clock.advance(Duration.ofSeconds(2)); // now inside the last minute
        assertThat(get(session, "/supplier/list")).isEqualTo(200);
        assertThat(stub.refreshes()).isEqualTo(1);
        assertThat(stub.logins()).isEqualTo(1);
        var calls = stub.seen("/supplier/list");
        assertThat(calls.get(2).authorization()).isNotEqualTo(first);
        assertThat(stub.seen("/auth/refresh").get(0).bodyText()).contains("refreshToken");
    }

    @Test
    @DisplayName("a 401 is answered by a refresh and one retry")
    void unauthorizedRefreshesAndRetries() throws Exception {
        var session = session();
        assertThat(get(session, "/sku/list/expand")).isEqualTo(200);
        stub.revokeAccess(); // the cost app no longer accepts the token we hold

        assertThat(get(session, "/sku/list/expand")).isEqualTo(200);

        assertThat(stub.refreshes()).isEqualTo(1);
        assertThat(stub.logins()).isEqualTo(1);
        assertThat(stub.seen("/sku/list/expand")).hasSize(3); // ok, 401, retried ok
    }

    @Test
    @DisplayName("a refused refresh falls back to a fresh sign-in")
    void refreshRefusedSignsInAgain() throws Exception {
        var session = session();
        assertThat(get(session, "/sku/list/expand")).isEqualTo(200);
        stub.revokeAccess();
        stub.refuseRefresh(true);

        assertThat(get(session, "/sku/list/expand")).isEqualTo(200);

        assertThat(stub.refreshes()).isEqualTo(1);
        assertThat(stub.logins()).isEqualTo(2);
    }

    @Test
    @DisplayName("401 after the refresh and after a new sign-in: give up with a plain error, three tries in all")
    void unauthorizedThreeTimesFails() throws Exception {
        var session = session();
        stub.requireAuth(false);
        stub.answer(401, "{\"message\":\"no\"}");

        assertThatThrownBy(() -> get(session, "/item-purchase/invoice/extract"))
                .isInstanceOf(CostApiUnavailableException.class)
                .hasMessage("The cost app refused the reader's sign-in.");
        assertThat(stub.seen("/item-purchase/invoice/extract")).hasSize(3);
        assertThat(stub.refreshes()).isEqualTo(1);
        assertThat(stub.logins()).isEqualTo(2);
    }

    @Test
    @DisplayName("a wrong password: a plain error, and no second sign-in for 30 s however often it is asked")
    void wrongPasswordBacksOff() throws Exception {
        stub.password("the-password-the-cost-app-wants");
        var session = session();

        assertThatThrownBy(() -> get(session, "/supplier/list"))
                .isInstanceOf(CostApiUnavailableException.class)
                .hasMessageContaining("refused the reader's sign-in (status 401)")
                .hasMessageContaining("INVOICE_READER_PASSWORD")
                .hasMessageNotContaining(CostAppStub.PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> get(session, "/supplier/list")).isInstanceOf(CostApiUnavailableException.class)
                    .hasMessageContaining("tried again shortly");
        }
        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(() -> get(session, "/supplier/list")).isInstanceOf(CostApiUnavailableException.class);
        assertThat(stub.logins()).isEqualTo(1);
        assertThat(stub.seen("/supplier/list")).isEmpty();

        // After the back-off, one new try; with the right password it works again.
        clock.advance(Duration.ofSeconds(2));
        stub.password(CostAppStub.PASSWORD);
        assertThat(get(session, "/supplier/list")).isEqualTo(200);
        assertThat(stub.logins()).isEqualTo(2);
    }

    @Test
    @DisplayName("ten callers at once: one sign-in, all served")
    void concurrentCallersShareOneLogin() throws Exception {
        stub.loginDelay(300);
        var session = session();
        var pool = Executors.newFixedThreadPool(10);
        var go = new CountDownLatch(1);
        var results = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return get(session, "/supplier/list");
            }));
        }
        go.countDown();
        for (var r : results) {
            assertThat(r.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(stub.logins()).isEqualTo(1);
        assertThat(stub.seen("/supplier/list")).hasSize(10);
    }

    @Test
    @DisplayName("a sign-in slower than the timeout is a plain failure, not a hang")
    void loginTimeout() {
        stub.loginDelay(2000);
        var session = session(CostAppStub.PASSWORD, Duration.ofMillis(300));
        long start = System.nanoTime();
        assertThatThrownBy(() -> get(session, "/supplier/list")).isInstanceOf(CostApiUnavailableException.class)
                .hasMessageContaining("could not be reached");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1800));
    }

    @Test
    @DisplayName("no token, refresh token or password is ever logged, put in an error, or shown by toString")
    void secretsNeverLogged() throws Exception {
        var session = session();
        get(session, "/supplier/list");                       // sign in
        clock.advance(Duration.ofSeconds(850));
        get(session, "/supplier/list");                       // refresh
        stub.revokeAccess();
        stub.refuseRefresh(true);
        get(session, "/supplier/list");                       // 401, refused refresh, sign in again
        var errors = new ArrayList<String>();
        stub.password("changed-on-the-cost-app-side");
        stub.revokeAccess();
        try {
            get(session, "/supplier/list");                   // 401, refused refresh, wrong password
        } catch (CostApiUnavailableException e) {
            errors.add(e.getMessage());
        }
        try {
            get(session, "/supplier/list");                   // backing off
        } catch (CostApiUnavailableException e) {
            errors.add(e.getMessage());
        }
        assertThat(errors).hasSize(2);
        assertThat(logs.list).isNotEmpty();

        var secrets = new ArrayList<>(stub.issuedTokens());
        secrets.add(CostAppStub.PASSWORD);
        assertThat(secrets).hasSizeGreaterThan(4);
        var written = new ArrayList<String>(errors);
        written.add(session.toString());
        for (ILoggingEvent event : logs.list) {
            written.add(event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                written.add(String.valueOf(t.getMessage()));
            }
        }
        for (String line : written) {
            for (String secret : secrets) {
                assertThat(line).describedAs("a log line or error").doesNotContain(secret);
            }
        }
        assertThat(session.toString()).contains("password=***");
    }

    @Test
    @DisplayName("D-115 (M1): only the calls in the enum can be made; sign-in calls, a GET with a body or a POST without one are refused before any I/O")
    void onlyTheAllowedCalls() throws Exception {
        var session = session();
        assertThatThrownBy(() -> session.call(CostApiSession.Call.LOGIN, java.util.Map.of(), null, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.call(CostApiSession.Call.REFRESH, java.util.Map.of(),
                new CostApiSession.Body("application/json", new byte[]{1}), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.call(CostApiSession.Call.SKU_LIST, java.util.Map.of(),
                new CostApiSession.Body("application/json", new byte[]{1}), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.call(CostApiSession.Call.EXTRACT, java.util.Map.of(), null,
                Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThat(stub.calls()).isZero();
        // A query value cannot change the path or the host: it is encoded.
        get(session, "/supplier/list");
        session.call(CostApiSession.Call.SKU_LIST, java.util.Map.of("outlet", "1/../../item-purchase/save?x=@evil.example"),
                null, Duration.ofSeconds(5));
        for (var seen : stub.seen()) {
            assertThat(java.util.Arrays.stream(CostApiSession.Call.values())
                    .anyMatch(c -> c.method().equals(seen.method()) && c.path().equals(seen.path())))
                    .describedAs(seen.method() + " " + seen.path()).isTrue();
        }
        // The enum is the whole list: exactly these five method and path pairs.
        assertThat(java.util.Arrays.stream(CostApiSession.Call.values()).map(c -> c.method() + " " + c.path()))
                .containsExactly("POST /auth/login", "POST /auth/refresh", "POST /item-purchase/invoice/extract",
                        "GET /supplier/list", "GET /sku/list/expand");
        // No public way to send anything else: the only public request method takes a Call.
        assertThat(java.util.Arrays.stream(CostApiSession.class.getMethods())
                .filter(m -> m.getDeclaringClass() == CostApiSession.class)
                .filter(m -> java.util.Arrays.asList(m.getParameterTypes()).contains(HttpRequest.class)
                        || m.getName().equals("send")))
                .isEmpty();
    }

    @Test
    @DisplayName("D-115 (M1): the base URL must be https (http only for localhost) and a redirect is never followed")
    void httpsOnlyAndNoRedirects() throws Exception {
        for (String bad : new String[]{"http://cost.example.com", "ftp://cost.example.com", "https://user:pw@cost.example.com",
                "https://cost.example.com/?x=1", "", "not a url"}) {
            assertThatThrownBy(() -> new CostApiSession(bad, "u", "p", () -> null, Duration.ofSeconds(1), clock))
                    .describedAs(bad).isInstanceOf(IllegalStateException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("pw@"));
        }
        new CostApiSession("https://cost.example.com/api/", "u", "p", () -> null, Duration.ofSeconds(1), clock);
        new CostApiSession("http://localhost:8080", "u", "p", () -> null, Duration.ofSeconds(1), clock);
        new CostApiSession("http://127.0.0.1:8080", "u", "p", () -> null, Duration.ofSeconds(1), clock);

        stub.requireAuth(false);
        stub.answer(() -> new CostAppStub.Reply(302, "{}", 0, stub.baseUrl() + "/item-purchase/save"));
        var session = CostApiSession.withStaticToken(stub.baseUrl(), () -> "static-test-token");
        int status = session.call(CostApiSession.Call.EXTRACT, java.util.Map.of(),
                new CostApiSession.Body("application/octet-stream", new byte[]{1}), Duration.ofSeconds(5)).statusCode();
        assertThat(status).isEqualTo(302);
        assertThat(stub.seen()).hasSize(1);
        assertThat(stub.seen("/item-purchase/save")).isEmpty();
    }

    @Test
    @DisplayName("D-115 (L4): a sign-in answered 200 with something that is not JSON backs off 30 s like any failed sign-in")
    void nonJsonLoginBacksOff() throws Exception {
        stub.loginAnswer("<html><body>Please log in to the Wi-Fi</body></html>");
        var session = session();
        assertThatThrownBy(() -> get(session, "/supplier/list")).isInstanceOf(CostApiUnavailableException.class)
                .hasMessage("The cost app's sign-in answer could not be understood.");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> get(session, "/supplier/list")).isInstanceOf(CostApiUnavailableException.class)
                    .hasMessageContaining("tried again shortly");
        }
        assertThat(stub.logins()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(31));
        stub.loginAnswer(null);
        assertThat(get(session, "/supplier/list")).isEqualTo(200);
        assertThat(stub.logins()).isEqualTo(2);
    }

    @Test
    @DisplayName("the JWT exp claim is read without verifying the token; anything else gives no expiry")
    void jwtExpiry() throws Exception {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"x\",\"exp\":1790000000}".getBytes(StandardCharsets.UTF_8));
        var method = CostApiSession.class.getDeclaredMethod("jwtExpiry", String.class);
        method.setAccessible(true);
        assertThat(method.invoke(null, "eyJhbGciOiJIUzI1NiJ9." + payload + ".not-a-real-signature"))
                .isEqualTo(Instant.ofEpochSecond(1790000000));
        assertThat(method.invoke(null, "opaque-token")).isNull();
        assertThat(method.invoke(null, "a.%%%.c")).isNull();
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
