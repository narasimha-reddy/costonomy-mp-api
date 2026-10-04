package com.costonomy.mp.billing;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixtures shared by the tax-invoice ITs: wallet-paid, weighed orders from a supplier with a GSTIN and HSN. */
@AutoConfigureMockMvc
abstract class TaxInvoiceTestBase extends AbstractIntegrationTest {

    private static final AtomicInteger GSTIN_SEQ = new AtomicInteger(1000);

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected OutboxPublisher outbox;
    @Autowired protected com.costonomy.mp.billing.service.BillingEventListener billingListener;

    protected ApiClient api;
    protected TestOrder orders;

    @BeforeEach
    void setUpBase() {
        api = new ApiClient(mvc, json);
        orders = new TestOrder(mvc, json, api);
    }

    protected record Buyer(String token, long outletId) {
    }

    protected record Seller(String token, long orgId, long storeId, long skuId, String gstin) {
    }

    protected record Placed(Buyer buyer, Seller seller, long orderId, long itemId) {
    }

    protected record Reply(int status, JsonNode body) {
        String errorCode() {
            return body.at("/error/code").asText();
        }
    }

    /** A GSTIN no other test uses (the column is unique): a Telangana (36) registration. */
    protected static String freshGstin() {
        return "36AABCU%04dR1ZX".formatted(GSTIN_SEQ.incrementAndGet());
    }

    protected Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                        "name", "Paradise",
                        "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                                "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                                "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, outletId);
    }

    /** A supplier with a GSTIN, one catch-weight KG SKU at Rs 100 + 5% GST, and (unless null) an HSN code. */
    protected Seller newSeller(String gstin, String hsn) throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "Fresh Meats Pvt Ltd " + GSTIN_SEQ.incrementAndGet(), "displayName", "Fresh Meats",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "Fresh Meats store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        long orgId = created.get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED', gstin = ? where id = ?", gstin, orgId);
        TestCatalog.tradesAroundTheClock(jdbc, orgId);
        long storeId = created.get("stores").get(0).get("id").asLong();
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, storeId);
        long productId = TestCatalog.freshProduct(jdbc, "chicken");
        long skuId = api.post(token, "/api/v1/supplier-stores/" + storeId + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "CHK-" + productId,
                        "name", "Chicken", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "100", "gstRate", "5")).at("/data/id").asLong();
        jdbc.update("update supplier_sku set is_catch_weight = 1, hsn_code = ? where id = ?", hsn, skuId);
        return new Seller(token, orgId, storeId, skuId, gstin);
    }

    /** A wallet-paid pickup order of 10 kg, confirmed at once. */
    protected Placed place(Buyer buyer, Seller seller) throws Exception {
        api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up", Map.of("amount", "5000.00"));
        var created = orders.place(buyer.token(), buyer.outletId(), seller.token(), seller.skuId(), 10, 10,
                "PICKUP", "WALLET", null);
        long itemId = jdbc.queryForObject("select id from supplier_order_item where supplier_order_id = ? limit 1",
                Long.class, created.orderId());
        return new Placed(buyer, seller, created.orderId(), itemId);
    }

    protected Placed placeNew(Seller seller) throws Exception {
        return place(newBuyer(), seller);
    }

    protected Reply post(String token, String path, Object body) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse();
        String text = result.getContentAsString();
        return new Reply(result.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    protected Reply get(String token, String path) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        String text = result.getContentAsString();
        return new Reply(result.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    protected Reply step(Placed p, String step) throws Exception {
        return post(p.seller().token(), "/api/v1/supplier-orders/" + p.orderId() + "/" + step, Map.of());
    }

    protected Reply weigh(Placed p, String reading) throws Exception {
        return post(p.seller().token(), "/api/v1/supplier-orders/" + p.orderId() + "/weights",
                Map.of("weights", List.of(Map.of("supplierOrderItemId", p.itemId(), "dispatchedWeight", reading))));
    }

    /** Preparing, weighed, ready: a settled order a supplier may invoice. */
    protected void readyAt(Placed p, String reading) throws Exception {
        org.assertj.core.api.Assertions.assertThat(step(p, "preparing").status()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(weigh(p, reading).status()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(step(p, "ready").status()).isEqualTo(200);
    }

    protected Reply receive(Placed p, String received, String damaged, String missing) throws Exception {
        return post(p.buyer().token(), "/api/v1/supplier-orders/" + p.orderId() + "/receive",
                Map.of("items", List.of(Map.of("supplierOrderItemId", p.itemId(),
                        "receivedQuantity", received, "damagedQuantity", damaged,
                        "missingQuantity", missing, "rejectionReason", "SHORT_DELIVERY"))));
    }

    /**
     * Delivers the order's stored {@code ReceivingCompleted} event to the billing listener, as the outbox would.
     *
     * <p>Not left to the global outbox: it is one queue shared by every test in the suite, drained a batch at a time,
     * so how soon this order's event is reached depends on what other tests left behind. The event row is real (it
     * is asserted to exist), and only its delivery is made deterministic. Listeners are idempotent, so a scheduled
     * drain that delivers it too changes nothing.
     */
    protected void deliverReceivingCompleted(long orderId) {
        var event = jdbc.queryForMap("""
                select event_id, payload_version, payload, actor_id, occurred_at from outbox_event
                 where aggregate_type = 'SUPPLIER_ORDER' and aggregate_id = ? and event_type = 'ReceivingCompleted'
                 order by id desc limit 1""", orderId);
        billingListener.onDomainEvent(new OutboxPublisher.DomainEventEnvelope(
                (String) event.get("event_id"), "ReceivingCompleted", "SUPPLIER_ORDER", orderId,
                (Integer) event.get("payload_version"), (String) event.get("payload"),
                event.get("actor_id") == null ? null : ((Number) event.get("actor_id")).longValue(), null,
                ((java.sql.Timestamp) event.get("occurred_at")).toInstant()));
    }

    protected String generatePath(Placed p) {
        return "/api/v1/supplier-orders/" + p.orderId() + "/tax-invoice/generate";
    }

    protected int invoiceRows(long orderId) {
        return jdbc.queryForObject("select count(*) from tax_invoice where supplier_order_id = ?",
                Integer.class, orderId);
    }

    protected int creditNoteRows(long orderId) {
        return jdbc.queryForObject("select count(*) from credit_note where supplier_order_id = ?",
                Integer.class, orderId);
    }

    protected int sequenceRows(String gstin) {
        return jdbc.queryForObject("select count(*) from document_sequence where scope_key = ?",
                Integer.class, gstin);
    }
}
