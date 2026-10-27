package com.costonomy.mp.support;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * A paid supplier order with a Costonomy delivery row, for tests of what follows a delivery: courier payables, refunds,
 * views. Built through the API like a real one, then the delivery is left for the test to move by SQL.
 */
public final class DeliveryFixture {

    private DeliveryFixture() {
    }

    public record Setup(long orderId, long deliveryId, long outletId, long storeId, long supplierId,
                        String buyerToken, String supplierToken) {
    }

    /** @param buyerFee what the restaurant was charged for delivery; @param supplierFunded the supplier's agreed share */
    public static Setup create(MockMvc mvc, ObjectMapper json, ApiClient api, JdbcTemplate jdbc,
                               MockPaymentProvider paymentProvider, String buyerFee, String supplierFunded) throws Exception {
        return build(mvc, json, api, jdbc, paymentProvider, null, buyerFee, supplierFunded);
    }

    /**
     * As {@link #create}, but the order is left where a stuck delivery leaves it: {@code READY_FOR_PICKUP}, with its money
     * captured, and the delivery not yet with a courier.
     */
    public static Setup readyForPickup(MockMvc mvc, ObjectMapper json, ApiClient api, JdbcTemplate jdbc,
                                       MockPaymentProvider paymentProvider,
                                       com.costonomy.mp.payment.service.PaymentJobs paymentJobs,
                                       String buyerFee, String supplierFunded) throws Exception {
        return build(mvc, json, api, jdbc, paymentProvider, paymentJobs, buyerFee, supplierFunded);
    }

    private static Setup build(MockMvc mvc, ObjectMapper json, ApiClient api, JdbcTemplate jdbc,
                               MockPaymentProvider paymentProvider,
                               com.costonomy.mp.payment.service.PaymentJobs paymentJobs,
                               String buyerFee, String supplierFunded) throws Exception {
        String buyerToken = api.loginFresh();
        long outletId = api.post(buyerToken, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();

        String supplierToken = api.loginFresh();
        JsonNode created = api.post(supplierToken, "/api/v1/suppliers", Map.of(
                "legalName", "Fixture Foods " + UUID.randomUUID().toString().substring(0, 8),
                "displayName", "Fixture Foods",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "Fixture Store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        long supplierId = created.get("id").asLong();
        LocalCommon.activate(supplierId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierId);
        long storeId = created.get("stores").get(0).get("id").asLong();
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, storeId);

        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = api.post(supplierToken, "/api/v1/supplier-stores/" + storeId + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "100.00", "gstRate", "0")).at("/data/id").asLong();

        var checkout = new TestCheckout(paymentProvider, api);
        var placed = new TestOrder(mvc, json, api).place(buyerToken, outletId, supplierToken,
                skuId, 10, 10, "SUPPLIER_DELIVERY", null, null);
        checkout.pay(buyerToken, placed.paymentId(), placed.providerOrderId());
        long orderId = placed.orderId();

        if (paymentJobs == null) {
            jdbc.update("update supplier_order set status = 'COMPLETED', delivery_fee = ?, delivery_funded_amount = ?, "
                    + "version = version + 1 where id = ?", new BigDecimal(buyerFee), new BigDecimal(supplierFunded), orderId);
        } else {
            for (String step : new String[] {"preparing", "ready"}) {
                var body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/supplier-orders/" + orderId + "/" + step)
                                .header("Authorization", "Bearer " + supplierToken)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                        .andReturn().getResponse().getContentAsString();
                if (!json.readTree(body).at("/error").isNull()) {
                    throw new IllegalStateException("Could not move order " + orderId + " to " + step + ": " + body);
                }
            }
            paymentJobs.capturePending();
            jdbc.update("update supplier_order set delivery_fee = ?, delivery_funded_amount = ?, version = version + 1 "
                    + "where id = ?", new BigDecimal(buyerFee), new BigDecimal(supplierFunded), orderId);
        }
        jdbc.update("""
                insert into delivery (supplier_order_id, outlet_id, supplier_store_id,
                                      mode, status, fee, currency, provider_code, provider_delivery_id,
                                      pickup_address, drop_address, requested_at, created_at, updated_at, version)
                values (?, ?, ?, 'COSTONOMY', 'PROVIDER_SELECTED', 0, 'INR', null, null,
                        'Pickup Address', 'Drop Address', now(6), now(6), now(6), 0)
                """, orderId, outletId, storeId);
        long deliveryId = jdbc.queryForObject("select id from delivery where supplier_order_id = ?", Long.class, orderId);
        return new Setup(orderId, deliveryId, outletId, storeId, supplierId, buyerToken, supplierToken);
    }

    /** A disabled provider row, so a test can name a payment rail without any quote or booking ever picking it. */
    public static void provider(JdbcTemplate jdbc, String code, String rail) {
        jdbc.update("""
                insert into delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority,
                                               payment_rail, created_at, updated_at)
                values (?, ?, 0, 0, 0, 999, ?, now(6), now(6))
                on duplicate key update payment_rail = values(payment_rail)
                """, code, code, rail);
    }
}
