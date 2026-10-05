package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Buyer;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditClockConfig;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helpers for the edge-case suites (races, money safety, permissions) that sit beside
 * {@link CreditWalletSupport} without editing it, so the parallel suites cannot conflict.
 * Everything goes through the public endpoints; the SQL is only for reading state back.
 */
final class CreditEdgeSupport {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    final CreditWalletSupport s;

    CreditEdgeSupport(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.s = new CreditWalletSupport(mvc, json, jdbc);
    }

    static LocalDate today() {
        return LocalDate.now(CreditClockConfig.ZONE);
    }

    /** One HTTP call; never throws on a non-2xx, so a refusal can be asserted. */
    Reply call(String method, String token, String path, String key, Object body) throws Exception {
        var request = "GET".equals(method)
                ? MockMvcRequestBuilders.get(path)
                : MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body == null ? Map.of() : body));
        request.header("Authorization", "Bearer " + token);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    // ── orders ───────────────────────────────────────────────────────────

    /** A request the supplier has answered in full, ready to be ordered against. Returns the intent id. */
    long answeredRequest(Line line, String unitPrice, int quantity) throws Exception {
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = s.api.post(line.seller().token(),
                "/api/v1/supplier-stores/" + line.seller().storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", unitPrice, "gstRate", "0")).at("/data/id").asLong();
        long intentId = s.api.post(line.buyer().token(),
                "/api/v1/outlets/" + line.buyer().outletId() + "/intent-items",
                Map.of("supplierSkuId", skuId, "quantity", quantity)).at("/data/id").asLong();
        long itemId = s.api.get(line.buyer().token(), "/api/v1/intents/" + intentId)
                .at("/data/items/0/id").asLong();
        s.api.post(line.buyer().token(), "/api/v1/intents/" + intentId + "/send", Map.of());
        s.keyed(line.seller().token(), "/api/v1/intents/" + intentId + "/respond",
                Map.of("lines", List.of(Map.of("intentItemId", itemId, "offeredQuantity", quantity))));
        return intentId;
    }

    /** Order on credit against an answered request. */
    Reply orderOnCredit(Buyer buyer, long intentId) throws Exception {
        return call("POST", buyer.token(), "/api/v1/intents/" + intentId + "/orders", UUID.randomUUID().toString(),
                Map.of("deliveryMode", "PICKUP", "paymentMethod", "CREDIT"));
    }

    // ── the supplier's levers ────────────────────────────────────────────

    Reply suspend(Line line, String reason) throws Exception {
        return call("POST", line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/suspend",
                null, Map.of("reason", reason));
    }

    Reply modifyLimit(Line line, String newLimit) throws Exception {
        return call("POST", line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/modify",
                null, Map.of("approvedLimit", newLimit, "creditPeriodDays", 30, "reason", "Exposure review"));
    }

    // ── claims ───────────────────────────────────────────────────────────

    Map<String, Object> claimBody(String amount) {
        var b = new HashMap<String, Object>();
        b.put("amount", amount);
        b.put("method", "UPI");
        b.put("reference", "UTR" + UUID.randomUUID().toString().substring(0, 8));
        b.put("paidOn", today().toString());
        return b;
    }

    Reply claim(String token, long invoice, String amount) throws Exception {
        return call("POST", token, "/api/v1/credit/invoices/" + invoice + "/claims", UUID.randomUUID().toString(),
                claimBody(amount));
    }

    Reply confirm(String token, long claimId, String amount) throws Exception {
        Map<String, Object> b = amount == null ? Map.of() : Map.of("amount", amount);
        return call("POST", token, "/api/v1/credit/claims/" + claimId + "/confirm", UUID.randomUUID().toString(), b);
    }

    Reply withdrawClaim(String token, long claimId) throws Exception {
        return call("POST", token, "/api/v1/credit/claims/" + claimId + "/withdraw", null, Map.of());
    }

    // ── reading state back ───────────────────────────────────────────────

    Map<String, Object> agreementRow(long agreementId) {
        return jdbc.queryForMap("select status, approved_limit, reserved_amount, utilized_amount, version, "
                + "suspension_source from credit_agreement where id = ?", agreementId);
    }

    BigDecimal dec(Map<String, Object> row, String column) {
        return (BigDecimal) row.get(column);
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** Tokens' own user id, for grants. */
    long userId(String token) throws Exception {
        return s.api.get(token, "/api/v1/auth/me").at("/data/user/id").asLong();
    }
}
