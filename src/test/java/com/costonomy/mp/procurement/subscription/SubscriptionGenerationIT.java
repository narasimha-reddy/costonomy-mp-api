package com.costonomy.mp.procurement.subscription;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscription order generation on the normal funding path (D-132). Each test builds its own restaurant and
 * store, so nothing here depends on what another test left in the shared database.
 */
@AutoConfigureMockMvc
class SubscriptionGenerationIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SubscriptionGenerationService generation;
    @Autowired private SubscriptionOrderGenerator generator;

    private ApiClient api;
    private final LocalDate tomorrow = LocalDate.now(SubscriptionService.ZONE).plusDays(1);

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private record World(String buyer, long outletId, String seller, long storeId, long skuId) {
    }

    /** A restaurant, and a store that delivers and sells 'Fresh Milk 1L' at Rs 60 + 5% GST. */
    private World world() throws Exception {
        String buyer = api.loginFresh();
        long outletId = api.post(buyer, "/api/v1/restaurants", Map.of(
                "name", "Curry Leaf",
                "firstOutlet", Map.of("name", "Madhapur", "addressLine1", "Hitech City",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500081",
                        "contactName", "Chef Rao", "contactPhone", "+919876543210",
                        "latitude", "17.4485", "longitude", "78.3748")))
                .at("/data/outlets/0/id").asLong();

        String seller = api.loginFresh();
        JsonNode supplier = api.post(seller, "/api/v1/suppliers", Map.of(
                "legalName", "Milk Supplies " + System.nanoTime() + " LLP", "displayName", "Fresh Dairy",
                "contactName", "Ramesh Ops", "contactPhone", "+919876500011",
                "firstStore", Map.of("name", "Dairy Central", "addressLine1", "Kondapur",
                        "city", "Hyderabad", "state", "Telangana",
                        "contactName", "Ramesh", "contactPhone", "+919876500012",
                        "latitude", "17.4622", "longitude", "78.3568"))).get("data");
        long storeId = supplier.get("stores").get(0).get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', verification_status = 'VERIFIED' where id = ?",
                supplier.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, supplier.get("id").asLong());
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, storeId);

        long productId = TestCatalog.freshProduct(jdbc, "Milk");
        jdbc.update("""
                insert into supplier_sku (supplier_store_id, canonical_product_id, sku_code, name, pack_size,
                                          pack_unit, status, created_at, updated_at, version)
                values (?, ?, 'SKU-MILK-1L', 'Fresh Milk 1L', 1.0, 'LTR', 'ACTIVE', now(6), now(6), 0)
                """, storeId, productId);
        long skuId = jdbc.queryForObject("select max(id) from supplier_sku where supplier_store_id = ?",
                Long.class, storeId);
        jdbc.update("""
                insert into supplier_offer (supplier_sku_id, supplier_store_id, canonical_product_id, selling_price,
                    gst_rate, availability, status, effective_from, created_at, updated_at, version)
                values (?, ?, ?, 60.00, 5.00, 'AVAILABLE', 'ACTIVE', now(6), now(6), now(6), 0)
                """, skuId, storeId, productId);
        return new World(buyer, outletId, seller, storeId, skuId);
    }

    private Map<String, Object> body(World w, String quantity, String frequency, String paymentMethod,
                                     String deliveryMode, LocalDate start) {
        var body = new java.util.HashMap<String, Object>();
        body.put("supplierStoreId", w.storeId());
        body.put("supplierSkuId", w.skuId());
        body.put("quantity", quantity);
        body.put("unit", "KG");   // ignored: the SKU's own unit is LTR
        body.put("frequency", frequency);
        body.put("deliveryMode", deliveryMode);
        body.put("paymentMethod", paymentMethod);
        body.put("startDate", start.toString());
        return body;
    }

    private long subscribe(World w, String quantity, String frequency, String paymentMethod) throws Exception {
        return api.post(w.buyer(), "/api/v1/outlets/" + w.outletId() + "/subscriptions",
                body(w, quantity, frequency, paymentMethod, "SUPPLIER_DELIVERY", tomorrow)).at("/data/id").asLong();
    }

    private void topUp(World w, String amount) throws Exception {
        api.post(w.buyer(), "/api/v1/outlets/" + w.outletId() + "/wallet/top-up", Map.of("amount", amount));
    }

    private int ordersFor(long subId, LocalDate date) {
        return jdbc.queryForObject("""
                select count(*) from supplier_order
                 where subscription_id = ? and scheduled_delivery_date = ? and status <> 'CANCELLED'
                """, Integer.class, subId, Date.valueOf(date));
    }

    private int ordersAnyStatus(long subId) {
        return jdbc.queryForObject("select count(*) from supplier_order where subscription_id = ?",
                Integer.class, subId);
    }

    private int debits(long outletId) {
        return jdbc.queryForObject("""
                select count(*) from wallet_transaction t join wallet w on w.id = t.wallet_id
                 where w.outlet_id = ? and t.kind = 'ORDER_PAYMENT'""", Integer.class, outletId);
    }

    private Map<String, Object> run(long subId, LocalDate date) {
        return jdbc.queryForMap("select * from subscription_run where subscription_id = ? and delivery_date = ?",
                subId, Date.valueOf(date));
    }

    private int deleteStatus(String token, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private String subStatus(long subId) {
        return jdbc.queryForObject("select status from subscription where id = ?", String.class, subId);
    }

    // ── funding ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("funding")
    class Funding {

        @Test
        @DisplayName("without a top-up nothing is generated and the failure is recorded; after a top-up a rerun makes one order")
        void noTopUpThenTopUp() throws Exception {
            var w = world();
            long subId = subscribe(w, "10", "DAILY", "WALLET");

            var first = generation.runFor(tomorrow);

            assertThat(first.failed()).isGreaterThanOrEqualTo(1);
            var failed = run(subId, tomorrow);
            assertThat(failed.get("outcome")).isEqualTo("FUNDING_FAILED");
            assertThat((BigDecimal) failed.get("amount")).isEqualByComparingTo("630.00");
            assertThat(failed.get("supplier_order_id")).isNull();
            // No order is left behind, nothing was debited, the subscription is still active.
            assertThat(ordersAnyStatus(subId)).isZero();
            assertThat(debits(w.outletId())).isZero();
            assertThat(subStatus(subId)).isEqualTo("ACTIVE");
            // The restaurant is told, once.
            assertThat(jdbc.queryForObject("""
                    select count(*) from outbox_event where event_type = 'SubscriptionFundingFailed'
                       and aggregate_id = ?""", Integer.class, subId)).isEqualTo(1);

            topUp(w, "1000.00");
            generation.runFor(tomorrow);

            assertThat(ordersFor(subId, tomorrow)).isEqualTo(1);
            long orderId = jdbc.queryForObject("select id from supplier_order where subscription_id = ?", Long.class, subId);
            assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, orderId))
                    .isEqualTo("CONFIRMED");
            assertThat(debits(w.outletId())).isEqualTo(1);
            var generated = run(subId, tomorrow);
            assertThat(generated.get("outcome")).isEqualTo("GENERATED");
            assertThat(((Number) generated.get("supplier_order_id")).longValue()).isEqualTo(orderId);
            assertThat((Integer) generated.get("attempts")).isEqualTo(2);

            // A further run, or the same hour's retry, changes nothing.
            generation.runFor(tomorrow);
            assertThat(ordersFor(subId, tomorrow)).isEqualTo(1);
            assertThat(debits(w.outletId())).isEqualTo(1);
            assertThat(run(subId, tomorrow).get("outcome")).isEqualTo("GENERATED");
        }

        @Test
        @DisplayName("one subscription that cannot be funded does not stop the next one in the same sweep")
        void oneFailureDoesNotStopTheSweep() throws Exception {
            var w = world();
            long tooBig = subscribe(w, "100", "DAILY", "WALLET");   // Rs 6,300
            long small = subscribe(w, "1", "DAILY", "WALLET");      // Rs 63
            topUp(w, "100.00");

            generation.runFor(tomorrow);

            assertThat(run(tooBig, tomorrow).get("outcome")).isEqualTo("FUNDING_FAILED");
            assertThat(run(small, tomorrow).get("outcome")).isEqualTo("GENERATED");
            assertThat(ordersFor(tooBig, tomorrow)).isZero();
            assertThat(ordersFor(small, tomorrow)).isEqualTo(1);
            assertThat(debits(w.outletId())).isEqualTo(1);
        }

        @Test
        @DisplayName("a credit subscription reserves, utilises and invoices through the normal release")
        void creditSubscriptionIsInvoiced() throws Exception {
            var w = world();
            mvc.perform(MockMvcRequestBuilders
                    .put("/api/v1/supplier-stores/" + w.storeId() + "/credit-policy")
                    .header("Authorization", "Bearer " + w.seller())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("creditEnabled", true,
                            "defaultCreditPeriodDays", 30, "defaultGracePeriodDays", 5))));
            long agreementId = api.post(w.buyer(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", w.storeId(), "outletId", w.outletId(),
                    "requestedLimit", "100000", "requestedDays", 30,
                    "purpose", "PROCUREMENT")).at("/data/id").asLong();
            api.post(w.seller(), "/api/v1/credit/agreements/" + agreementId + "/approve", Map.of());
            long subId = subscribe(w, "10", "DAILY", "CREDIT");

            generation.runFor(tomorrow);

            long orderId = jdbc.queryForObject("select id from supplier_order where subscription_id = ?", Long.class, subId);
            assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, orderId))
                    .isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("select status from credit_reservation where supplier_order_id = ?",
                    String.class, orderId)).isEqualTo("UTILIZED");
            assertThat(jdbc.queryForObject("select amount from credit_invoice where supplier_order_id = ?",
                    BigDecimal.class, orderId)).isEqualByComparingTo("630.00");
            assertThat(jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?",
                    BigDecimal.class, agreementId)).isEqualByComparingTo("630.00");
        }

        @Test
        @DisplayName("two generations of the same date at once make one order and take the money once")
        void parallelGenerationsMakeOneOrder() throws Exception {
            var w = world();
            long subId = subscribe(w, "10", "DAILY", "WALLET");
            topUp(w, "1000.00");

            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return generator.generate(subId, tomorrow);
                    } catch (RuntimeException ex) {
                        return null;
                    }
                }));
            }
            start.countDown();
            int made = 0;
            for (var f : results) {
                if (f.get() != null) {
                    made++;
                }
            }
            pool.shutdown();

            assertThat(made).isEqualTo(1);
            assertThat(ordersFor(subId, tomorrow)).isEqualTo(1);
            assertThat(debits(w.outletId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select balance from wallet where outlet_id = ?", BigDecimal.class,
                    w.outletId())).isEqualByComparingTo("370.00");
        }

        @Test
        @DisplayName("the database refuses a second live order for the same subscription and date")
        void uniqueKeyRefusesADuplicateOrder() throws Exception {
            var w = world();
            long subId = subscribe(w, "10", "DAILY", "WALLET");
            topUp(w, "1000.00");
            generator.generate(subId, tomorrow);
            long orderId = jdbc.queryForObject("select id from supplier_order where subscription_id = ?", Long.class, subId);

            // Copy the live order's row: the key must stop it, whatever the application does.
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                    () -> jdbc.update("""
                            insert into supplier_order (supplier_store_id, outlet_id, order_number, status, payment_method,
                                payment_status, subtotal, gst_amount, total_amount, accepted_amount, delivery_mode,
                                delivery_fee, scheduled_delivery_date, is_subscription_order, subscription_id,
                                created_at, updated_at, version)
                            select supplier_store_id, outlet_id, concat(order_number, '-DUP'), 'DRAFT', payment_method,
                                'PENDING', subtotal, gst_amount, total_amount, accepted_amount, delivery_mode,
                                delivery_fee, scheduled_delivery_date, 1, subscription_id, now(6), now(6), 0
                              from supplier_order where id = ?""", orderId));

            // A cancelled order does not hold the date.
            jdbc.update("update supplier_order set status = 'CANCELLED' where id = ?", orderId);
            assertThat(ordersFor(subId, tomorrow)).isZero();
        }
    }

    // ── what is ordered and how ──────────────────────────────────────────

    @Nested
    @DisplayName("what is ordered")
    class Ordered {

        @Test
        @DisplayName("an item with no available offer is skipped, never ordered at Rs 0")
        void noOfferSkips() throws Exception {
            var w = world();
            long subId = subscribe(w, "10", "DAILY", "WALLET");
            topUp(w, "1000.00");
            jdbc.update("update supplier_offer set availability = 'OUT_OF_STOCK' where supplier_sku_id = ?", w.skuId());

            generation.runFor(tomorrow);

            assertThat(run(subId, tomorrow).get("outcome")).isEqualTo("SKIPPED_NO_OFFER");
            assertThat(ordersAnyStatus(subId)).isZero();
            assertThat(debits(w.outletId())).isZero();

            jdbc.update("update supplier_offer set availability = 'AVAILABLE', status = 'INACTIVE' where supplier_sku_id = ?",
                    w.skuId());
            generation.runFor(tomorrow);
            assertThat(ordersAnyStatus(subId)).isZero();
        }

        @Test
        @DisplayName("the line carries the SKU's unit and its catch-weight and cold-chain flags")
        void lineCarriesSkuFacts() throws Exception {
            var w = world();
            jdbc.update("update supplier_sku set is_catch_weight = 1, requires_cold_chain = 1 where id = ?", w.skuId());
            long subId = subscribe(w, "10", "DAILY", "WALLET");
            topUp(w, "1000.00");

            generation.runFor(tomorrow);

            var line = jdbc.queryForMap("""
                    select i.unit, i.is_catch_weight, i.requires_cold_chain, o.has_cold_chain_items
                      from supplier_order_item i join supplier_order o on o.id = i.supplier_order_id
                     where o.subscription_id = ?""", subId);
            assertThat(line.get("unit")).isEqualTo("LTR");
            assertThat(line.get("is_catch_weight")).isIn(true, 1);
            assertThat(line.get("requires_cold_chain")).isIn(true, 1);
            assertThat(line.get("has_cold_chain_items")).isIn(true, 1);
        }

        @Test
        @DisplayName("a slot that stopped being available, or a store that stopped delivering, is skipped with a reason and notified")
        void invalidAtGenerationIsSkipped() throws Exception {
            var w = world();
            long slotId = api.post(w.seller(), "/api/v1/supplier-stores/" + w.storeId() + "/delivery-slots", Map.of(
                    "slotName", "Morning", "startTime", "06:00:00", "endTime", "09:00:00",
                    "orderCutoffTime", "04:00:00", "maxOrdersPerDay", 30)).at("/data/id").asLong();
            var request = body(w, "10", "DAILY", "WALLET", "SUPPLIER_DELIVERY", tomorrow);
            request.put("preferredSlotId", slotId);
            long subId = api.post(w.buyer(), "/api/v1/outlets/" + w.outletId() + "/subscriptions", request)
                    .at("/data/id").asLong();
            topUp(w, "1000.00");
            jdbc.update("update delivery_slot set is_active = 0 where id = ?", slotId);

            generation.runFor(tomorrow);

            var skipped = run(subId, tomorrow);
            assertThat(skipped.get("outcome")).isEqualTo("SKIPPED_INVALID");
            assertThat((String) skipped.get("reason")).contains("slot");
            assertThat(ordersAnyStatus(subId)).isZero();
            assertThat(debits(w.outletId())).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from outbox_event where event_type = 'SubscriptionOrderSkipped'
                       and aggregate_id = ?""", Integer.class, subId)).isEqualTo(1);
            assertThat(subStatus(subId)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("a paused subscription is recorded as skipped and makes no order")
        void pausedIsRecorded() throws Exception {
            var w = world();
            long subId = subscribe(w, "10", "DAILY", "WALLET");
            topUp(w, "1000.00");
            assertThat(api.patchStatus(w.buyer(), "/api/v1/subscriptions/" + subId + "/pause", Map.of())).isEqualTo(200);

            generation.runFor(tomorrow);

            assertThat(run(subId, tomorrow).get("outcome")).isEqualTo("SKIPPED_PAUSED");
            assertThat(ordersAnyStatus(subId)).isZero();
            assertThat(debits(w.outletId())).isZero();
        }
    }

    // ── schedule ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("schedule")
    class Schedule {

        @Test
        @DisplayName("weekly orders only on the start weekday; alternate days only on even offsets")
        void frequencies() throws Exception {
            var w = world();
            long weekly = subscribe(w, "1", "WEEKLY", "WALLET");
            long alternate = subscribe(w, "1", "ALTERNATE_DAYS", "WALLET");
            topUp(w, "5000.00");

            for (int offset = 0; offset < 8; offset++) {
                generation.runFor(tomorrow.plusDays(offset));
            }

            var weeklyDates = jdbc.queryForList("""
                    select scheduled_delivery_date from supplier_order where subscription_id = ?
                     order by scheduled_delivery_date""", LocalDate.class, weekly);
            assertThat(weeklyDates).containsExactly(tomorrow, tomorrow.plusDays(7));
            var alternateDates = jdbc.queryForList("""
                    select scheduled_delivery_date from supplier_order where subscription_id = ?
                     order by scheduled_delivery_date""", LocalDate.class, alternate);
            assertThat(alternateDates).containsExactly(tomorrow, tomorrow.plusDays(2),
                    tomorrow.plusDays(4), tomorrow.plusDays(6));
        }
    }

    // ── who may change it, what may be created ───────────────────────────

    @Nested
    @DisplayName("access and validation")
    class Access {

        @Test
        @DisplayName("a member who can only view the outlet cannot pause, resume, cancel or skip; supplier staff cannot cancel")
        void viewOnlyCannotChange() throws Exception {
            var w = world();
            long subId = subscribe(w, "10", "DAILY", "WALLET");
            String viewerPhone = ApiClient.freshPhone();
            api.post(w.buyer(), "/api/v1/outlets/" + w.outletId() + "/users", Map.of(
                    "phone", viewerPhone, "roleCode", "REST_RECEIVING_STAFF", "name", "Receiver"));
            String viewer = api.login(viewerPhone);

            assertThat(api.getStatus(viewer, "/api/v1/subscriptions/" + subId)).isEqualTo(200);
            assertThat(api.patchStatus(viewer, "/api/v1/subscriptions/" + subId + "/pause", Map.of())).isEqualTo(404);
            assertThat(subStatus(subId)).isEqualTo("ACTIVE");
            assertThat(deleteStatus(viewer, "/api/v1/subscriptions/" + subId + "?reason=x"))
                    .isEqualTo(404);
            assertThat(api.postStatus(viewer, "/api/v1/subscriptions/" + subId + "/skip-dates", Map.of(
                    "skipDate", tomorrow.plusDays(3).toString(), "reason", "x"))).isEqualTo(404);
            assertThat(deleteStatus(w.seller(), "/api/v1/subscriptions/" + subId + "?reason=x"))
                    .isEqualTo(404);
            assertThat(subStatus(subId)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select count(*) from subscription_skip_date where subscription_id = ?",
                    Integer.class, subId)).isZero();

            // The supplier can still see it.
            assertThat(api.getStatus(w.seller(), "/api/v1/supplier-stores/" + w.storeId() + "/subscriptions"))
                    .isEqualTo(200);
            // The owner can.
            assertThat(api.patchStatus(w.buyer(), "/api/v1/subscriptions/" + subId + "/pause", Map.of())).isEqualTo(200);
            assertThat(subStatus(subId)).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("creation refuses other payment methods, Costonomy delivery and credit without an agreement, saving nothing")
        void creationRefusals() throws Exception {
            var w = world();
            String path = "/api/v1/outlets/" + w.outletId() + "/subscriptions";

            assertThat(api.postStatus(w.buyer(), path,
                    body(w, "10", "DAILY", "PREPAID", "SUPPLIER_DELIVERY", tomorrow))).isEqualTo(400);
            assertThat(api.postStatus(w.buyer(), path,
                    body(w, "10", "DAILY", "WALLET", "COSTONOMY_DELIVERY", tomorrow))).isEqualTo(400);
            assertThat(api.postStatus(w.buyer(), path,
                    body(w, "10", "DAILY", "CREDIT", "SUPPLIER_DELIVERY", tomorrow))).isEqualTo(400);

            assertThat(jdbc.queryForObject("select count(*) from subscription where outlet_id = ?",
                    Integer.class, w.outletId())).isZero();
        }

        @Test
        @DisplayName("there is no longer an endpoint that lets a supplier trigger generation")
        void noSupplierTrigger() throws Exception {
            var w = world();
            subscribe(w, "10", "DAILY", "WALLET");
            topUp(w, "1000.00");

            int status = api.postStatus(w.seller(), "/api/v1/supplier-stores/" + w.storeId()
                    + "/subscriptions/generate-orders?date=" + tomorrow, Map.of());

            assertThat(status).isIn(404, 405);
            assertThat(jdbc.queryForObject("select count(*) from supplier_order o join subscription s on s.id = o.subscription_id "
                    + "where s.outlet_id = ?", Integer.class, w.outletId())).isZero();
        }
    }
}
