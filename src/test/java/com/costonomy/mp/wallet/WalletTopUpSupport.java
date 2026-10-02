package com.costonomy.mp.wallet;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * What every wallet top-up integration test needs: a restaurant, the three
 * endpoints, and a way to look at the ledger without going through the code
 * under test (D-107).
 */
final class WalletTopUpSupport {

    record Buyer(String token, long outletId) {
    }

    /** A response's status and body. */
    record Reply(int status, JsonNode body) {
        String code() {
            return body.at("/error/code").asText();
        }

        JsonNode data() {
            return body.at("/data");
        }
    }

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final ApiClient api;

    WalletTopUpSupport(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.api = new ApiClient(mvc, json);
    }

    ApiClient api() {
        return api;
    }

    Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                        "name", "Paradise",
                        "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                                "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                                "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, outletId);
    }

    long userId(String token) throws Exception {
        return api.get(token, "/api/v1/auth/me").at("/data/user/id").asLong();
    }

    void grant(long userId, long outletId, String roleCode) {
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'OUTLET', ?, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = ?
                """, userId, outletId, roleCode);
    }

    Reply call(String method, String token, String path, Object body, String idempotencyKey) throws Exception {
        var request = "GET".equals(method) ? MockMvcRequestBuilders.get(path) : MockMvcRequestBuilders.post(path);
        request.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString();
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    Reply create(Buyer buyer, String amount, String key) throws Exception {
        return create(buyer.token(), buyer.outletId(), amount, key);
    }

    Reply create(String token, long outletId, String amount, String key) throws Exception {
        return call("POST", token, "/api/v1/outlets/" + outletId + "/wallet/top-ups",
                Map.of("amount", amount), key);
    }

    /** Creates a top-up that must succeed; returns its data. */
    JsonNode createOk(Buyer buyer, String amount) throws Exception {
        var reply = create(buyer, amount, UUID.randomUUID().toString());
        if (reply.status() != 200) {
            throw new AssertionError("create " + amount + " → " + reply.status() + " " + reply.body());
        }
        return reply.data();
    }

    Reply confirm(Buyer buyer, long topUpId, String paymentId, String signature) throws Exception {
        return confirm(buyer.token(), buyer.outletId(), topUpId, paymentId, signature);
    }

    Reply confirm(String token, long outletId, long topUpId, String paymentId, String signature) throws Exception {
        return call("POST", token, "/api/v1/outlets/" + outletId + "/wallet/top-ups/" + topUpId + "/confirm",
                Map.of("razorpayPaymentId", paymentId, "razorpaySignature", signature), null);
    }

    Reply confirm(Buyer buyer, long topUpId, String paymentId) throws Exception {
        return confirm(buyer, topUpId, paymentId, MockPaymentProvider.TEST_SIGNATURE);
    }

    Reply status(Buyer buyer, long topUpId) throws Exception {
        return call("GET", buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-ups/" + topUpId,
                null, null);
    }

    Reply wallet(Buyer buyer) throws Exception {
        return call("GET", buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet", null, null);
    }

    // ── straight from the database, not through the code under test ──────

    BigDecimal balance(Buyer buyer) {
        return jdbc.queryForObject(
                "select coalesce((select balance from wallet where outlet_id = ?), 0)",
                BigDecimal.class, buyer.outletId());
    }

    /** What the ledger says the balance is: credits less debits. */
    BigDecimal ledgerSum(Buyer buyer) {
        return jdbc.queryForObject("""
                select coalesce(sum(case when t.direction = 'CREDIT' then t.amount else -t.amount end), 0)
                  from wallet_transaction t join wallet w on w.id = t.wallet_id where w.outlet_id = ?
                """, BigDecimal.class, buyer.outletId());
    }

    int ledgerRows(long topUpId) {
        return jdbc.queryForObject("select count(*) from wallet_transaction where reference = ?",
                Integer.class, "topup-" + topUpId);
    }

    String dbStatus(long topUpId) {
        return jdbc.queryForObject("select status from wallet_top_up where id = ?", String.class, topUpId);
    }

    int topUpRows(Buyer buyer) {
        return jdbc.queryForObject("select count(*) from wallet_top_up where outlet_id = ?",
                Integer.class, buyer.outletId());
    }

    /**
     * Puts credited_at at {@code offsetSeconds} from the given instant, in SQL on
     * the database's own clock: a Timestamp bound from Java would be shifted by
     * this JVM's zone, and the boundary under test is a matter of hours.
     */
    void creditedAt(java.time.Instant target, long offsetSeconds, long... topUpIds) {
        long secondsAgo = java.time.Duration.between(target.plusSeconds(offsetSeconds), java.time.Instant.now())
                .getSeconds();
        for (long id : topUpIds) {
            jdbc.update("update wallet_top_up set credited_at = date_sub(utc_timestamp(6), interval ? second) where id = ?",
                    secondsAgo, id);
        }
    }

    void backdate(long topUpId, String column, int minutes) {
        jdbc.update("update wallet_top_up set " + column
                + " = date_sub(utc_timestamp(6), interval ? minute) where id = ?", minutes, topUpId);
    }
}
