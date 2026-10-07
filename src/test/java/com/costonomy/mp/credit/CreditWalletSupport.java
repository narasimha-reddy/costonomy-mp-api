package com.costonomy.mp.credit;

import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the wallet-repayment tests need to stand up a credit line with invoices and a funded wallet, going
 * through the public endpoints like {@code CreditFlowIT} does (its helpers are private to that class).
 */
final class CreditWalletSupport {

    record Buyer(String token, long outletId) {
    }

    record Seller(String token, long storeId) {
    }

    record Line(Buyer buyer, Seller seller, long agreementId) {
    }

    record Fx(Line line, long first, long second) {
    }

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
    final ApiClient api;

    CreditWalletSupport(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.api = new ApiClient(mvc, json);
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

    Seller newSeller() throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "ABC Foods Pvt Ltd", "displayName", "ABC Foods",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "ABC store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, created.get("id").asLong());
        return new Seller(token, created.get("stores").get(0).get("id").asLong());
    }

    /** A restaurant and a supplier with a live line between them (supplier offers credit, approves as asked). */
    Line creditLine(String limit, Map<String, Object> approval) throws Exception {
        return creditLine(newBuyer(), limit, approval);
    }

    /** A new supplier's live line to an existing buyer's outlet. */
    Line creditLine(Buyer buyer, String limit, Map<String, Object> approval) throws Exception {
        var seller = newSeller();
        mvc.perform(MockMvcRequestBuilders
                .put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                .header("Authorization", "Bearer " + seller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("creditEnabled", true,
                        "defaultCreditPeriodDays", 30, "defaultGracePeriodDays", 5))));
        long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                "requestedLimit", limit, "requestedDays", 30, "purpose", "PROCUREMENT")).at("/data/id").asLong();
        api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve", approval);
        return new Line(buyer, seller, agreementId);
    }

    Line creditLine(String limit) throws Exception {
        return creditLine(limit, Map.of());
    }

    /** Orders on credit for unitPrice x quantity; the order's invoice is raised on acceptance. Returns its id. */
    long invoice(Line line, String unitPrice, int quantity) throws Exception {
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = api.post(line.seller().token(),
                "/api/v1/supplier-stores/" + line.seller().storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", unitPrice, "gstRate", "0")).at("/data/id").asLong();
        long intentId = api.post(line.buyer().token(),
                "/api/v1/outlets/" + line.buyer().outletId() + "/intent-items",
                Map.of("supplierSkuId", skuId, "quantity", quantity)).at("/data/id").asLong();
        long itemId = api.get(line.buyer().token(), "/api/v1/intents/" + intentId)
                .at("/data/items/0/id").asLong();
        api.post(line.buyer().token(), "/api/v1/intents/" + intentId + "/send", Map.of());
        keyed(line.seller().token(), "/api/v1/intents/" + intentId + "/respond",
                Map.of("lines", List.of(Map.of("intentItemId", itemId, "offeredQuantity", quantity))));
        keyed(line.buyer().token(), "/api/v1/intents/" + intentId + "/orders",
                Map.of("deliveryMode", "PICKUP", "paymentMethod", "CREDIT"));
        return jdbc.queryForObject("select i.id from credit_invoice i join supplier_order o on o.id = i.supplier_order_id "
                + "where i.credit_agreement_id = ? order by i.id desc limit 1", Long.class, line.agreementId());
    }

    JsonNode keyed(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }

    void topUp(Buyer buyer, String amount) throws Exception {
        api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up", Map.of("amount", amount));
    }

    BigDecimal balance(Buyer buyer) {
        return jdbc.queryForObject("select coalesce((select balance from wallet where outlet_id = ?), 0)",
                BigDecimal.class, buyer.outletId());
    }

    /** Due date and overdue-after in the past, so the sweep treats the invoice as late. */
    void age(long invoiceId, int daysPastDue) {
        jdbc.update("update credit_invoice set due_date = date_sub(curdate(), interval ? day), "
                + "overdue_after = date_sub(curdate(), interval ? day) where id = ?",
                daysPastDue + 5, daysPastDue, invoiceId);
    }

    Reply repay(String token, long agreementId, String key, Map<String, Object> body) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/v1/credit/agreements/" + agreementId + "/wallet-repayments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    Reply recordPayment(Seller seller, long invoiceId, String amount) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/credit/invoices/" + invoiceId + "/payments")
                        .header("Authorization", "Bearer " + seller.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", amount, "method", "BANK_TRANSFER",
                                "reference", "UTR12345678"))))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    /** One snapshot of everything a refusal must leave alone. */
    record Snapshot(BigDecimal wallet, long walletRows, long repayments, long creditPayments, BigDecimal utilized,
                    List<Map<String, Object>> invoices, long ledgerRows, long outbox) {
    }

    Snapshot snapshot(Line line) {
        return new Snapshot(balance(line.buyer()),
                jdbc.queryForObject("select count(*) from wallet_transaction t join wallet w on w.id = t.wallet_id "
                        + "where w.outlet_id = ?", Long.class, line.buyer().outletId()),
                jdbc.queryForObject("select count(*) from credit_repayment where outlet_id = ?", Long.class,
                        line.buyer().outletId()),
                jdbc.queryForObject("select count(*) from credit_payment where credit_agreement_id = ?", Long.class,
                        line.agreementId()),
                jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?", BigDecimal.class,
                        line.agreementId()),
                jdbc.queryForList("select id, status, paid_amount, settled_at from credit_invoice "
                        + "where credit_agreement_id = ? order by id", line.agreementId()),
                jdbc.queryForObject("select count(*) from credit_transaction where credit_agreement_id = ?",
                        Long.class, line.agreementId()),
                jdbc.queryForObject("select count(*) from outbox_event where json_extract(payload, '$.outletId') = ?",
                        Long.class, line.buyer().outletId()));
    }
}
