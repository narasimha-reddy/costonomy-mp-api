package com.costonomy.mp.wallet.invoice.costapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Our service sign-in to the Costonomy cost app (D-114). The cost app issues short-lived access tokens
 * ({@code POST {base}/auth/login} with {@code {username, password, provider:"local"}} answers
 * {@code {accessToken, refreshToken, tokenType, expiresIn, user}}; {@code POST {base}/auth/refresh} with
 * {@code {refreshToken}} answers {@code {accessToken, tokenType, expiresIn}}: a new access token only).
 *
 * <ul>
 *   <li>Signs in lazily, on the first call that needs a token, and keeps the access token in memory with its
 *       expiry ({@code expiresIn}, else the JWT {@code exp} claim read without verifying it, else 15 minutes).</li>
 *   <li>Renews 60 seconds before expiry with the refresh token; a refused refresh falls back to a sign-in.</li>
 *   <li>A 401 on any call: refresh once and retry, then sign in again once and retry, then give up.</li>
 *   <li>One sign-in at a time: the others wait for it and use its token.</li>
 *   <li>A failed sign-in is not repeated for 30 seconds: every caller in that window is told the reader is
 *       unavailable without the cost app being asked again.</li>
 *   <li>Tokens and the password are never logged, never put in an exception message and never returned.</li>
 * </ul>
 *
 * <p>A static token setting, when present, overrides all of this (quick tests only): it is read on every call,
 * never renewed, and a 401 with it is final.
 *
 * <p><b>Only the calls in {@link Call} can be made (D-115).</b> The session builds every URL itself from the
 * configured base URL and the call's fixed method and path; a caller passes only query values and, for the one POST,
 * a body. No other method, path or host can be expressed. The base URL must be https (plain http only for
 * localhost, for tests), and redirects are never followed, so the token never leaves for another host.
 */
@Slf4j
public class CostApiSession {

    public static final Duration REFRESH_AHEAD = Duration.ofSeconds(60);
    public static final Duration LOGIN_BACKOFF = Duration.ofSeconds(30);
    static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /**
     * Every request this code can send to the cost app (D-115): a fixed method and path each. Sign-in and refresh
     * are made by the session itself; the other three take the bearer token. Nothing here writes to the cost app
     * except what EXTRACT does on its side (it reads the bill; it does not save a purchase).
     */
    public enum Call {
        LOGIN("POST", "/auth/login"),
        REFRESH("POST", "/auth/refresh"),
        EXTRACT("POST", "/item-purchase/invoice/extract"),
        SUPPLIER_LIST("GET", "/supplier/list"),
        SKU_LIST("GET", "/sku/list/expand");

        private final String method;
        private final String path;

        Call(String method, String path) {
            this.method = method;
            this.path = path;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }
    }

    /** A request body for {@link Call#EXTRACT}: its content type and bytes. */
    public record Body(String contentType, byte[] content) {
        @Override
        public String toString() {
            return "Body[" + contentType + ", " + (content == null ? 0 : content.length) + " bytes]";
        }
    }

    private final String baseUrl;
    private final String username;
    private final String password;
    private final Supplier<String> staticToken;
    private final Duration authTimeout;
    private final Clock clock;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Tokens tokens;
    private volatile Instant loginFailedAt;
    private volatile String loginProblem;

    /** What the cost app gave us. Its {@code toString} never shows a token. */
    private record Tokens(String access, Instant accessExpiresAt, String refresh, Instant refreshExpiresAt) {
        @Override
        public String toString() {
            return "Tokens[access=***, expiresAt=" + accessExpiresAt + ", refresh=" + (refresh == null ? "none" : "***") + "]";
        }
    }

    public CostApiSession(String baseUrl, String username, String password, Supplier<String> staticToken,
                          Duration authTimeout, Clock clock) {
        this.baseUrl = checkedBaseUrl(baseUrl);
        this.username = username == null ? "" : username.strip();
        this.password = password == null ? "" : password;
        this.staticToken = staticToken == null ? () -> null : staticToken;
        this.authTimeout = authTimeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * The base URL, refused unless it is https, or http to localhost (tests). No user info, query or fragment. The
     * messages name the setting, never its value.
     */
    static String checkedBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "costonomy.mp.invoices.reader.base-url is required when the invoice reader provider is HTTP.");
        }
        String trimmed = baseUrl.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.base-url is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.base-url must be a plain https URL "
                    + "(no user info, query or fragment).");
        }
        boolean local = LOCAL_HOSTS.contains(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && local)) {
            throw new IllegalStateException("costonomy.mp.invoices.reader.base-url must use https; "
                    + "plain http is allowed only for localhost.");
        }
        return trimmed;
    }

    /** A session that only ever uses the given token (the static override). */
    public static CostApiSession withStaticToken(String baseUrl, Supplier<String> token) {
        return new CostApiSession(baseUrl, "", "", token, Duration.ofSeconds(15), Clock.systemUTC());
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Makes one of the allowed calls with the current access token. The URL is built here from the base URL, the
     * call's fixed path and {@code query} (each value URL-encoded); GETs carry no body and the POST must carry one.
     * A 401 is answered by a refresh and a retry, then a fresh sign-in and a retry; a third 401 is a
     * {@link CostApiUnavailableException}. Every other answer is returned as it is.
     */
    public HttpResponse<byte[]> call(Call call, Map<String, String> query, Body body, Duration timeout)
            throws IOException, InterruptedException {
        if (call == Call.LOGIN || call == Call.REFRESH) {
            throw new IllegalArgumentException("Sign-in calls are made by the session itself.");
        }
        if ("GET".equals(call.method()) && body != null) {
            throw new IllegalArgumentException("A GET carries no body.");
        }
        if ("POST".equals(call.method()) && (body == null || body.content() == null)) {
            throw new IllegalArgumentException("This call needs a body.");
        }
        URI uri = uri(call, query);
        String override = staticToken.get();
        if (override != null && !override.isBlank()) {
            var response = http.send(request(call, uri, body, timeout, override.strip()),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 401) {
                throw new CostApiUnavailableException("The cost app refused the reader's token.");
            }
            return response;
        }
        String token = accessToken();
        var response = http.send(request(call, uri, body, timeout, token), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 401) {
            return response;
        }
        log.info("Cost app answered 401; renewing the access token");
        token = renew(token, false);
        response = http.send(request(call, uri, body, timeout, token), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 401) {
            return response;
        }
        log.info("Cost app answered 401 again; signing in afresh");
        token = renew(token, true);
        response = http.send(request(call, uri, body, timeout, token), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 401) {
            return response;
        }
        throw new CostApiUnavailableException("The cost app refused the reader's sign-in.");
    }

    /** The call's URL on the configured base, checked to be on the same scheme, host and port. */
    URI uri(Call call, Map<String, String> query) {
        var q = new StringBuilder();
        if (query != null) {
            for (var e : query.entrySet()) {
                q.append(q.length() == 0 ? "?" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
        }
        URI base = URI.create(baseUrl);
        URI uri = URI.create(baseUrl + call.path() + q);
        if (!base.getScheme().equalsIgnoreCase(uri.getScheme()) || !base.getHost().equalsIgnoreCase(uri.getHost())
                || base.getPort() != uri.getPort()) {
            throw new IllegalArgumentException("Not the configured cost app.");
        }
        return uri;
    }

    private static HttpRequest request(Call call, URI uri, Body body, Duration timeout, String token) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if ("POST".equals(call.method())) {
            builder.header("Content-Type", body.contentType())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.content()));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** A current access token, signing in or refreshing first when needed. */
    String accessToken() {
        Tokens t = tokens;
        if (fresh(t)) {
            return t.access();
        }
        return locked(() -> {
            Tokens again = tokens;
            if (fresh(again)) {
                return again.access(); // another caller renewed it while we waited
            }
            if (refreshUsable(again)) {
                try {
                    return refresh(again).access();
                } catch (RefreshRefused e) {
                    log.info("Cost app refused the token refresh; signing in again");
                }
            }
            return login().access();
        });
    }

    /** After a 401 with {@code stale}: refresh (falling back to a sign-in), or sign in when {@code forceLogin}. */
    private String renew(String stale, boolean forceLogin) {
        return locked(() -> {
            Tokens current = tokens;
            if (current != null && !current.access().equals(stale) && fresh(current)) {
                return current.access(); // already renewed by someone else
            }
            if (!forceLogin && refreshUsable(current)) {
                try {
                    return refresh(current).access();
                } catch (RefreshRefused e) {
                    log.info("Cost app refused the token refresh; signing in again");
                }
            }
            tokens = null;
            return login().access();
        });
    }

    private boolean fresh(Tokens t) {
        return t != null && clock.instant().isBefore(t.accessExpiresAt().minus(REFRESH_AHEAD));
    }

    private boolean refreshUsable(Tokens t) {
        return t != null && t.refresh() != null
                && (t.refreshExpiresAt() == null || clock.instant().isBefore(t.refreshExpiresAt()));
    }

    private String locked(Supplier<String> work) {
        boolean got;
        try {
            got = lock.tryLock(authTimeout.toMillis() * 2 + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CostApiUnavailableException("Signing in to the cost app was interrupted.");
        }
        if (!got) {
            throw new CostApiUnavailableException("Signing in to the cost app is taking too long.");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    // ── sign-in and refresh (called with the lock held) ─────────────────

    private Tokens login() {
        if (username.isEmpty() || password.isEmpty()) {
            throw new CostApiUnavailableException("The bill reader has no cost-app sign-in configured.");
        }
        Instant failedAt = loginFailedAt;
        if (failedAt != null && clock.instant().isBefore(failedAt.plus(LOGIN_BACKOFF))) {
            throw new CostApiUnavailableException(loginProblem + " Signing in is tried again shortly.");
        }
        var body = new LinkedHashMap<String, String>();
        body.put("username", username);
        body.put("password", password);
        body.put("provider", "local");
        HttpResponse<byte[]> response;
        try {
            response = post(Call.LOGIN, body);
        } catch (IOException e) {
            throw loginFailed("The cost app's sign-in could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CostApiUnavailableException("Signing in to the cost app was interrupted.");
        }
        int status = response.statusCode();
        if (status == 400 || status == 401 || status == 403) {
            throw loginFailed("The cost app refused the reader's sign-in (status " + status
                    + "). Check INVOICE_READER_USERNAME and INVOICE_READER_PASSWORD.");
        }
        if (status / 100 != 2) {
            throw loginFailed("The cost app's sign-in answered with status " + status + ".");
        }
        JsonNode node;
        try {
            node = parse(response.body());
        } catch (CostApiUnavailableException e) {
            // An HTML page from a proxy or a captive portal answered 200: back off like any failed sign-in.
            throw loginFailed("The cost app's sign-in answer could not be understood.");
        }
        String access = text(node, "accessToken");
        if (access == null) {
            throw loginFailed("The cost app's sign-in answer had no access token.");
        }
        String refresh = text(node, "refreshToken");
        Tokens t = new Tokens(access, expiry(node, access), refresh, refresh == null ? null : jwtExpiry(refresh));
        tokens = t;
        loginFailedAt = null;
        loginProblem = null;
        log.info("Signed in to the cost app; the access token is good until {}", t.accessExpiresAt());
        return t;
    }

    private CostApiUnavailableException loginFailed(String problem) {
        tokens = null;
        loginFailedAt = clock.instant();
        loginProblem = problem;
        log.warn("Cost app sign-in failed: {}", problem);
        return new CostApiUnavailableException(problem);
    }

    private Tokens refresh(Tokens current) {
        HttpResponse<byte[]> response;
        try {
            var body = new LinkedHashMap<String, String>();
            body.put("refreshToken", current.refresh());
            response = post(Call.REFRESH, body);
        } catch (IOException e) {
            throw new RefreshRefused();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CostApiUnavailableException("Signing in to the cost app was interrupted.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new RefreshRefused();
        }
        JsonNode node;
        try {
            node = parse(response.body());
        } catch (CostApiUnavailableException e) {
            throw new RefreshRefused();
        }
        String access = text(node, "accessToken");
        if (access == null) {
            throw new RefreshRefused();
        }
        // The cost app's refresh answers an access token only; keep the refresh token we have unless it sends one.
        String refresh = text(node, "refreshToken");
        Tokens t = refresh == null
                ? new Tokens(access, expiry(node, access), current.refresh(), current.refreshExpiresAt())
                : new Tokens(access, expiry(node, access), refresh, jwtExpiry(refresh));
        tokens = t;
        log.info("Renewed the cost app access token; good until {}", t.accessExpiresAt());
        return t;
    }

    private HttpResponse<byte[]> post(Call call, Object body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri(call, Map.of())).timeout(authTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new CostApiUnavailableException("The cost app's sign-in answer could not be understood.");
            }
            return node;
        } catch (IOException e) {
            throw new CostApiUnavailableException("The cost app's sign-in answer could not be understood.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().strip() : null;
    }

    /** {@code expiresIn} seconds when given, else the token's own {@code exp}, else 15 minutes. */
    private Instant expiry(JsonNode node, String access) {
        Instant now = clock.instant();
        JsonNode in = node.get("expiresIn");
        if (in != null && in.canConvertToLong() && in.asLong() > 0) {
            return now.plusSeconds(in.asLong());
        }
        Instant exp = jwtExpiry(access);
        return exp != null ? exp : now.plus(DEFAULT_LIFETIME);
    }

    /**
     * The {@code exp} claim of a JWT, read without verifying the signature: it only decides when we renew, and
     * the cost app is the one that checks the token. Null when the token is not a JWT or has no {@code exp}.
     */
    static Instant jwtExpiry(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = new ObjectMapper().readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode exp = claims == null ? null : claims.get("exp");
            return exp != null && exp.canConvertToLong() ? Instant.ofEpochSecond(exp.asLong()) : null;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    /** Whether we hold an access token right now (for diagnostics; never the token). */
    public boolean signedIn() {
        return tokens != null;
    }

    @Override
    public String toString() {
        return "CostApiSession[baseUrl=" + baseUrl + ", username=" + (username.isEmpty() ? "unset" : "set")
                + ", password=" + (password.isEmpty() ? "unset" : "***") + ", signedIn=" + signedIn() + "]";
    }

    /** The refresh token was refused or the answer was unusable: fall back to a sign-in. */
    private static final class RefreshRefused extends RuntimeException {
        RefreshRefused() {
            super("refresh refused", null, false, false);
        }
    }
}
