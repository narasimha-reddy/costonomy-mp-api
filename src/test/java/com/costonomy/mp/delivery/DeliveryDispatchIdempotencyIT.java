package com.costonomy.mp.delivery;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.OutboxQuiet;
import net.javacrumbs.shedlock.core.LockProvider;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestCheckout;
import com.costonomy.mp.support.TestOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-194 follow-up: auto-dispatch must not book a second courier when the event's own transaction fails AFTER the
 * provider has already accepted the booking (a later listener, the outbox commit, a lost connection). The booking
 * commits in its own transaction, so the retry finds the delivery and books nothing.
 */
@AutoConfigureMockMvc
@Import(DeliveryDispatchIdempotencyIT.FailAfterDispatch.class)
class DeliveryDispatchIdempotencyIT extends AbstractIntegrationTest {

    /** Makes the event transaction of one order fail at commit time, once, after every listener has run. */
    public static class FailAfterDispatch {
        static final AtomicLong failOrder = new AtomicLong(-1);

        @EventListener
        public void on(OutboxPublisher.DomainEventEnvelope e) {
            if ("SupplierOrderReady".equals(e.eventType()) && e.aggregateId() != null
                    && failOrder.compareAndSet(e.aggregateId(), -1)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("simulated failure after the booking");
                    }
                });
            }
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxPublisher publisher;
    @Autowired private TestPaymentAccess payments;

    @Autowired @Qualifier("mockExpressDeliveryProvider") private MockDeliveryProvider express;
    @Autowired @Qualifier("mockSaverDeliveryProvider") private MockDeliveryProvider saver;

    private ApiClient api;
    private TestCheckout checkout;
    private TestOrder orders;

    @Autowired private LockProvider lockProvider;

    @BeforeEach
    void setUp() throws Exception {
        OutboxQuiet.awaitLockFree(lockProvider); // the context's start-up drain must not race this test's own drains
        api = new ApiClient(mvc, json);
        checkout = new TestCheckout(payments.provider(), api);
        orders = new TestOrder(mvc, json, api);
        express.disarm();
        saver.disarm();
        FailAfterDispatch.failOrder.set(-1);
    }

    private long orderPreparing() throws Exception {
        String buyer = api.loginFresh();
        long outletId = api.post(buyer, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "contactName", "Asha", "contactPhone", "+919876511111",
                        "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        String seller = api.loginFresh();
        JsonNode created = api.post(seller, "/api/v1/suppliers", Map.of(
                "legalName", "ABC Foods Pvt Ltd", "displayName", "ABC Foods",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("name", "ABC store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "contactName", "Imran", "contactPhone", "+919876522222",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, created.get("id").asLong());
        long storeId = created.get("stores").get(0).get("id").asLong();
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = api.post(seller, "/api/v1/supplier-stores/" + storeId + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
        var placed = orders.place(buyer, outletId, seller, skuId, 10, 10, "COSTONOMY_DELIVERY", null, null);
        checkout.pay(buyer, placed.paymentId(), placed.providerOrderId());
        post(seller, "/api/v1/supplier-orders/" + placed.orderId() + "/preparing");
        // The test profile never drains on its own (costonomy.mp.outbox.drain-on-commit=false, poll PT1H), so this
        // test decides when the events are relayed. Quiet what is pending so only this order's events are in play.
        jdbc.update("update outbox_event set status = 'PUBLISHED', published_at = now(3) where status = 'PENDING'");
        FailAfterDispatch.failOrder.set(placed.orderId());
        post(seller, "/api/v1/supplier-orders/" + placed.orderId() + "/ready");
        return placed.orderId();
    }

    private void post(String token, String path) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private Map<String, Object> readyEvent(long orderId) {
        return jdbc.queryForMap("select status, attempt_count, last_error from outbox_event "
                + "where event_type = 'SupplierOrderReady' and aggregate_type = 'SUPPLIER_ORDER' and aggregate_id = ?",
                orderId);
    }

    @Test
    void aFailureAfterTheBookingRetriesWithoutBookingASecondCourier() throws Exception {
        long orderId = orderPreparing();

        publisher.drainUnlocked(); // books, then the event's own commit fails

        var first = readyEvent(orderId);
        assertThat(first.get("status")).isEqualTo("PENDING");
        assertThat(first.get("attempt_count")).isEqualTo(1);
        assertThat(first.get("last_error").toString()).contains("simulated failure after the booking");
        assertThat(express.bookCalls(orderId) + saver.bookCalls(orderId)).describedAs("bookings so far").isEqualTo(1);

        jdbc.update("update outbox_event set next_attempt_at = date_sub(utc_timestamp(3), interval 1 minute) "
                + "where status = 'PENDING' and event_type = 'SupplierOrderReady' and aggregate_id = ?", orderId);
        publisher.drainUnlocked(); // the retry

        assertThat(readyEvent(orderId).get("status")).isEqualTo("PUBLISHED");
        assertThat(express.bookCalls(orderId) + saver.bookCalls(orderId))
                .describedAs("provider booking calls after the retry").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from delivery where supplier_order_id = ?",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from delivery_ledger l join delivery d on d.id = l.delivery_id
                 where d.supplier_order_id = ? and l.entry_type = 'BOOKED'""", Integer.class, orderId)).isEqualTo(1);
    }
}
