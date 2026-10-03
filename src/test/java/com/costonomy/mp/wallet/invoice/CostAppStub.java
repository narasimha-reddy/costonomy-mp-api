package com.costonomy.mp.wallet.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A local stand-in for the cost app (D-113, D-114). Never a real service. It records what it is sent and answers
 * like the real one: {@code POST /auth/login} and {@code POST /auth/refresh} issue made-up tokens,
 * {@code /item-purchase/invoice/extract} answers an array shaped like the real one (including the internal fields of
 * the cost app's SKU and supplier records and its users, which must never reach us), and {@code GET /supplier/list}
 * and {@code GET /sku/list/expand} answer lists with internal fields too.
 *
 * <p>With {@link #requireAuth(boolean)} off (the default) any request is answered, as a static-token reader expects;
 * with it on, only an access token the stub issued and has not revoked is accepted.
 */
public final class CostAppStub implements AutoCloseable {

    public static final String SKU_MARKER = "INTERNAL-ITEM-MASTER-7741";
    public static final String SUPPLIER_MARKER = "INTERNAL-SUPPLIER-ACME-9920";
    public static final String USER_MARKER = "internal.user.rao";
    public static final String COST_MARKER = "123456.789";
    /** Made-up sign-in for tests; not a credential anywhere. */
    public static final String USERNAME = "reader-test-user";
    public static final String PASSWORD = "stub-password-for-tests-only-5521";

    public record Seen(String method, String path, String query, String authorization, String contentType,
                       byte[] body) {
        public String bodyText() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    /** One SKU as the cost app's {@code SKUDetailFullResponse} serialises it, internal fields and all. */
    public static String sku(long id, String name, String itemPrice) {
        return """
                {"id":%1$d,"outletId":77,"itemId":5,"item":{"id":5,"itemName":"%2$s","alias":"%2$s"},
                 "skuName":"%3$s","brandName":"Acme","unit":"KG","unitQuantity":1,"itemUnitQuantity":1,"itemUnit":"KG",
                 "unitPrice":%4$s,"itemPrice":%5$s,"initialItemPrice":111,"sourceItemCode":"%2$s","typeId":1,
                 "type":"Raw Material","categoryId":4,"categoryName":"Seafood","yieldPercentage":%4$s,
                 "isPerishable":true,"hsnCode":"0306-%2$s","alias":"chef","consumptionRank":3,"masterItemId":8,
                 "isDisabled":false,"createdBy":3,"updatedBy":3,"createdTs":"2026-01-01T00:00:00",
                 "createdByUser":{"id":3,"name":"%6$s"},"updatedByUser":{"id":3,"name":"%6$s"},"warnings":[]}
                """.formatted(id, SKU_MARKER, name, COST_MARKER, itemPrice, USER_MARKER);
    }

    public static String supplier(long id, String name, boolean disabled) {
        return """
                {"id":%1$d,"outletId":77,"supplierName":"%2$s","contactName":"%3$s","phone":"9999999999",
                 "email":"x@example.invalid","address":"%3$s","taxId":"36ABCDE1234F1Z5","supplierTypeId":1,
                 "supplierType":{"id":1,"name":"%3$s"},"fixedExpenseId":4,"fixedExpense":{"id":4},"countryId":1,
                 "stateId":36,"cityId":500,"isDisabled":%4$s,"createdBy":3,"updatedBy":3,
                 "createdByUser":{"id":3,"name":"%5$s"},"updatedByUser":{"id":3,"name":"%5$s"}}
                """.formatted(id, name, SUPPLIER_MARKER, disabled, USER_MARKER);
    }

    public static final String GOOD = """
            [
              {"fileName":"page-1.jpg","error":"Could not read this page","items":[{"itemName":"WRONG FIRST","totalPrice":1}],"totalAmount":1},
              {
                "invoiceNumber":"1631","invoiceDate":"04/09/26","dueDate":"2026-10-04",
                "vendorName":"KOSTA Delights","vendorAddress":"Hyderabad","customerName":"Delicia","customerAddress":"Jubilee Hills",
                "items":[
                  {"sno":1,"itemName":"16/20 prawns","description":"d","uom":"KG","hsn":"0306","quantity":2,"unitPrice":560,"totalPrice":1120,
                   "taxableAmount":1120,"taxAmount":0,"cgstAmount":0,"sgstAmount":0,"unitQuantity":1,"upc":"%1$s","sourceItemCode":"%1$s",
                   "sku":%2$s},
                  {"sno":2,"itemName":"21/25 prawns","uom":"KG","quantity":"2","unitPrice":"450","totalPrice":"900",
                   "sku":%3$s},
                  {"sno":3,"itemName":"30/50 prawns","uom":"KG","quantity":2,"unitPrice":400,"totalPrice":800,"sku":null}
                ],
                "subtotal":2820,"taxAmount":0,"deliveryCharges":0,"totalAmount":2820,"currency":"INR","paymentTerms":"Net 7",
                "supplier":%4$s,
                "createdByUser":{"id":3,"name":"%5$s"},
                "invoices":[]
              }
            ]
            """.formatted(SKU_MARKER, sku(9465, "Prawns 16/20", "360"), sku(152, "PRAWNS 21/25", "300"),
            supplier(2001, "Kosta Delights - Sea Food", false), USER_MARKER);

    public static final String SUPPLIERS = "[" + supplier(2001, "Kosta Delights - Sea Food", false) + ","
            + supplier(2002, "Ganesh Vegetables", false) + "," + supplier(2003, "Old Closed Supplier", true) + "]";

    public static final String SKUS = "[" + sku(9465, "Prawns 16/20", "360") + "," + sku(152, "PRAWNS 21/25", "300")
            + "," + sku(9001, "Prawns 30/40", "270") + "," + sku(9100, "Onion", "40") + "]";

    public record Reply(int status, String body, long delayMillis, String location) {
        public Reply(int status, String body, long delayMillis) {
            this(status, body, delayMillis, null);
        }
    }

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final Set<String> validAccess = ConcurrentHashMap.newKeySet();
    private final Set<String> validRefresh = ConcurrentHashMap.newKeySet();
    private final List<String> issued = new CopyOnWriteArrayList<>();
    private volatile Supplier<Reply> reply = () -> new Reply(200, GOOD, 0);
    private volatile boolean requireAuth;
    private volatile boolean refuseRefresh;
    private volatile String password = PASSWORD;
    private volatile long expiresIn = 900;
    private volatile long loginDelayMillis;
    private volatile String loginBody;
    private final java.util.Map<String, String[]> listsByOutlet = new ConcurrentHashMap<>();

    public CostAppStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            var t = new Thread(r, "cost-app-stub");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        seen.add(new Seen(method, path, exchange.getRequestURI().getRawQuery(), auth,
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        calls.incrementAndGet();
        Reply r;
        if ("POST".equals(method) && "/auth/login".equals(path)) {
            r = login(body);
        } else if ("POST".equals(method) && "/auth/refresh".equals(path)) {
            r = refresh(body);
        } else if (requireAuth && (auth == null || !auth.startsWith("Bearer ")
                || !validAccess.contains(auth.substring(7)))) {
            r = new Reply(401, "{\"message\":\"Unauthorized\"}", 0);
        } else if ("GET".equals(method) && "/supplier/list".equals(path)) {
            String[] lists = listsByOutlet.get(outletOf(exchange.getRequestURI().getRawQuery()));
            r = new Reply(200, lists != null ? lists[0] : SUPPLIERS, 0);
        } else if ("GET".equals(method) && "/sku/list/expand".equals(path)) {
            String[] lists = listsByOutlet.get(outletOf(exchange.getRequestURI().getRawQuery()));
            r = new Reply(200, lists != null ? lists[1] : SKUS, 0);
        } else {
            r = reply.get();
        }
        if (r.delayMillis() > 0) {
            try {
                Thread.sleep(r.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (r.location() != null) {
            exchange.getResponseHeaders().add("Location", r.location());
        }
        try {
            exchange.sendResponseHeaders(r.status(), out.length);
            exchange.getResponseBody().write(out);
        } catch (IOException ignored) {
            // the client gave up (a timeout test)
        } finally {
            exchange.close();
        }
    }

    private static String outletOf(String query) {
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("outlet=")) {
                    return part.substring("outlet=".length());
                }
            }
        }
        return "";
    }

    private Reply login(byte[] body) {
        logins.incrementAndGet();
        if (loginBody != null) {
            return new Reply(200, loginBody, 0);
        }
        JsonNode in;
        try {
            in = json.readTree(body);
        } catch (IOException e) {
            return new Reply(400, "{\"message\":\"bad\"}", 0);
        }
        boolean ok = in != null && USERNAME.equals(in.path("username").asText())
                && password.equals(in.path("password").asText()) && "local".equals(in.path("provider").asText());
        if (!ok) {
            return new Reply(401, "{\"message\":\"Invalid username or password\"}", loginDelayMillis);
        }
        String access = token("access");
        String refresh = token("refresh");
        validAccess.add(access);
        validRefresh.add(refresh);
        return new Reply(200, """
                {"accessToken":"%s","refreshToken":"%s","tokenType":"Bearer","expiresIn":%d,
                 "user":{"id":6,"userName":"%s","outletId":77,"roleId":2,"password":null}}
                """.formatted(access, refresh, expiresIn, USERNAME), loginDelayMillis);
    }

    private Reply refresh(byte[] body) {
        refreshes.incrementAndGet();
        String given;
        try {
            given = json.readTree(body).path("refreshToken").asText();
        } catch (IOException e) {
            return new Reply(400, "{}", 0);
        }
        if (refuseRefresh || !validRefresh.contains(given)) {
            return new Reply(401, "{\"message\":\"Invalid or expired refresh token\"}", 0);
        }
        String access = token("access");
        validAccess.add(access);
        // Like the real one: a new access token only.
        return new Reply(200, "{\"accessToken\":\"%s\",\"tokenType\":\"Bearer\",\"expiresIn\":%d}"
                .formatted(access, expiresIn), 0);
    }

    private String token(String kind) {
        String t = "stub-" + kind + "-" + UUID.randomUUID();
        issued.add(t);
        return t;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Seen> seen() {
        return seen;
    }

    public List<Seen> seen(String path) {
        return seen.stream().filter(s -> s.path().equals(path)).toList();
    }

    public int calls() {
        return calls.get();
    }

    public int logins() {
        return logins.get();
    }

    public int refreshes() {
        return refreshes.get();
    }

    /** Every token the stub ever handed out: none may appear in a log or an answer of ours. */
    public List<String> issuedTokens() {
        return List.copyOf(issued);
    }

    public void answer(int status, String body) {
        this.reply = () -> new Reply(status, body, 0);
    }

    public void answer(Supplier<Reply> reply) {
        this.reply = reply;
    }

    public void requireAuth(boolean on) {
        this.requireAuth = on;
    }

    /** Every access token issued so far stops working, as when they expire on the cost app's side. */
    public void revokeAccess() {
        validAccess.clear();
    }

    public void refuseRefresh(boolean on) {
        this.refuseRefresh = on;
    }

    /** The password the stub accepts from now on. */
    public void password(String accepted) {
        this.password = accepted;
    }

    public void expiresIn(long seconds) {
        this.expiresIn = seconds;
    }

    public void loginDelay(long millis) {
        this.loginDelayMillis = millis;
    }

    /** The supplier and SKU lists answered for one cost outlet ({@code ?outlet=}); others get the default lists. */
    public void lists(String outlet, String suppliers, String skus) {
        listsByOutlet.put(outlet, new String[]{suppliers, skus});
    }

    /** Every sign-in is answered 200 with this body (an HTML page from a proxy, say); null: the normal answer. */
    public void loginAnswer(String body) {
        this.loginBody = body;
    }

    public void reset() {
        listsByOutlet.clear();
        loginBody = null;
        seen.clear();
        calls.set(0);
        logins.set(0);
        refreshes.set(0);
        requireAuth = false;
        refuseRefresh = false;
        password = PASSWORD;
        expiresIn = 900;
        loginDelayMillis = 0;
        this.reply = () -> new Reply(200, GOOD, 0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
