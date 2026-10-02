package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.slot.DeliverySlotDtos;
import com.costonomy.mp.procurement.subscription.SubscriptionDtos;
import com.costonomy.mp.procurement.subscription.SubscriptionFrequency;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
class DeliverySlotsAndSubscriptionsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    @Test
    @DisplayName("Delivery slots and recurring subscriptions end-to-end flow")
    void slotsAndSubscriptionsFlow() throws Exception {
        // 1. Create Buyer (Restaurant) & Seller (Supplier)
        String buyerToken = api.loginFresh();
        long outletId = api.post(buyerToken, "/api/v1/restaurants", Map.of(
                "name", "Curry Leaf",
                "firstOutlet", Map.of("name", "Madhapur", "addressLine1", "Hitech City",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500081",
                        "contactName", "Chef Rao", "contactPhone", "+919876543210",
                        "latitude", "17.4485", "longitude", "78.3748")))
                .at("/data/outlets/0/id").asLong();

        String sellerToken = api.loginFresh();
        JsonNode supplierRes = api.post(sellerToken, "/api/v1/suppliers", Map.of(
                "legalName", "Fresh Milk & Dairy Supplies LLP", "displayName", "Fresh Dairy",
                "contactName", "Ramesh Ops", "contactPhone", "+919876500011",
                "firstStore", Map.of("name", "Dairy Central", "addressLine1", "Kondapur",
                        "city", "Hyderabad", "state", "Telangana",
                        "contactName", "Ramesh", "contactPhone", "+919876500012",
                        "latitude", "17.4622", "longitude", "78.3568"))).get("data");

        long storeId = supplierRes.get("stores").get(0).get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', verification_status = 'VERIFIED' where id = ?",
                supplierRes.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, supplierRes.get("id").asLong());

        // 2. Supplier creates delivery slot
        JsonNode slotCreated = api.post(sellerToken, "/api/v1/supplier-stores/" + storeId + "/delivery-slots", Map.of(
                "slotName", "Morning Slot (06:00 - 09:00)",
                "startTime", "06:00:00",
                "endTime", "09:00:00",
                "orderCutoffTime", "04:00:00",
                "maxOrdersPerDay", 30
        ));
        long preferredSlotId = slotCreated.at("/data/id").asLong();
        assertThat(preferredSlotId).isGreaterThan(0);

        // 3. Buyer queries available delivery slots for tomorrow
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        JsonNode availableSlots = api.get(buyerToken, "/api/v1/supplier-stores/" + storeId + "/available-slots?date=" + tomorrow);
        assertThat(availableSlots.at("/data").isArray()).isTrue();
        assertThat(availableSlots.at("/data").size()).isGreaterThanOrEqualTo(1);

        String slotName = availableSlots.at("/data/0/slotName").asText();
        assertThat(slotName).isEqualTo("Morning Slot (06:00 - 09:00)");

        // 3. Create a SKU and offer for subscription
        long canonicalId = jdbc.queryForObject("select min(id) from canonical_product", Long.class);
        jdbc.update("""
                insert into supplier_sku (supplier_store_id, canonical_product_id, sku_code, name, pack_size, pack_unit, status, created_at, updated_at, version)
                values (?, ?, 'SKU-MILK-1L', 'Fresh Milk 1L', 1.0, 'LTR', 'ACTIVE', now(6), now(6), 0)
                """, storeId, canonicalId);
        long skuId = jdbc.queryForObject("select max(id) from supplier_sku where supplier_store_id = ?", Long.class, storeId);

        jdbc.update("""
                insert into supplier_offer (supplier_sku_id, supplier_store_id, canonical_product_id, selling_price, gst_rate, availability, status, effective_from, created_at, updated_at, version)
                values (?, ?, ?, 60.00, 5.00, 'AVAILABLE', 'ACTIVE', now(6), now(6), now(6), 0)
                """, skuId, storeId, canonicalId);

        // 4. Restaurant subscribes to daily replenishment
        JsonNode subRes = api.post(buyerToken, "/api/v1/outlets/" + outletId + "/subscriptions", Map.of(
                "supplierStoreId", storeId,
                "supplierSkuId", skuId,
                "quantity", 10.00,
                "unit", "LTR",
                "frequency", "DAILY",
                "preferredSlotId", preferredSlotId,
                "deliveryMode", "SUPPLIER_DELIVERY",
                "startDate", tomorrow.toString(),
                "notes", "Morning chai supply"
        ));

        assertThat(subRes.at("/data/id").asLong()).isGreaterThan(0);
        long subId = subRes.at("/data/id").asLong();
        assertThat(subRes.at("/data/status").asText()).isEqualTo("ACTIVE");
        assertThat(subRes.at("/data/preferredSlotName").asText()).isEqualTo(slotName);

        // 5. Add a skip date
        LocalDate dayAfterTomorrow = tomorrow.plusDays(1);
        JsonNode skipRes = api.post(buyerToken, "/api/v1/subscriptions/" + subId + "/skip-dates", Map.of(
                "skipDate", dayAfterTomorrow.toString(),
                "reason", "Restaurant maintenance day"
        ));
        assertThat(skipRes.at("/data/skipDates").size()).isGreaterThanOrEqualTo(1);

        // 6. Supplier views daily manifest for tomorrow
        JsonNode manifestRes = api.get(sellerToken, "/api/v1/supplier-stores/" + storeId + "/subscriptions/manifest?date=" + tomorrow);
        assertThat(manifestRes.at("/data/aggregatedItems").size()).isGreaterThanOrEqualTo(1);
        assertThat(manifestRes.at("/data/deliveries").size()).isGreaterThanOrEqualTo(1);

        // 7. Supplier triggers daily replenishment order generation for tomorrow
        JsonNode genRes = api.post(sellerToken, "/api/v1/supplier-stores/" + storeId + "/subscriptions/generate-orders?date=" + tomorrow, Map.of());
        assertThat(genRes.at("/data/ordersGenerated").asInt()).isEqualTo(1);
        long orderId = genRes.at("/data/orderIds/0").asLong();

        // 8. Verify generated order has is_subscription_order = 1 and delivery_slot_id
        JsonNode orderRes = api.get(buyerToken, "/api/v1/supplier-orders/" + orderId);
        assertThat(orderRes.at("/data/isSubscriptionOrder").asBoolean()).isTrue();
        assertThat(orderRes.at("/data/deliverySlotId").asLong()).isEqualTo(preferredSlotId);
        assertThat(orderRes.at("/data/deliverySlotName").asText()).isEqualTo(slotName);
        assertThat(orderRes.at("/data/scheduledDeliveryDate").asText()).isEqualTo(tomorrow.toString());
    }
}
