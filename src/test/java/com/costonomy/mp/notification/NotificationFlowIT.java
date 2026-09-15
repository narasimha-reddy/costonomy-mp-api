package com.costonomy.mp.notification;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.notification.service.NotificationDispatcher;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notifications and analytics end to end. Doc 08, doc 04 §18, §23A.27.
 *
 * <p>Four properties carry this suite. <b>A notification reaches the people with a
 * grant</b>, not just whoever acted. <b>Critical notifications ignore
 * preferences</b> (doc 08 §5) while everything else respects them. <b>Delivery is
 * retried with bounded backoff and gives up on a permanent failure</b>. And
 * <b>analytics never stores a secret</b>, because the server strips them rather
 * than trusting clients not to send them (doc 08 §8).
 */
@AutoConfigureMockMvc
class NotificationFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationRelayAccess relay;
    @Autowired private NotificationDispatcher dispatcher;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private record Buyer(String token, long userId, long outletId) {
    }

    private record Seller(String token, long userId, long storeId) {
    }

    private Buyer newBuyer() throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, userId, outletId);
    }

    private Seller newSeller() throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "ABC Foods Pvt Ltd", "displayName", "ABC Foods",
                "firstStore", Map.of("name", "ABC store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        return new Seller(token, userId, created.get("stores").get(0).get("id").asLong());
    }

    /** Register a device so push has somewhere to go. */
    private void registerDevice(String token, String pushToken) throws Exception {
        api.post(token, "/api/v1/devices", Map.of(
                "platform", "ANDROID", "pushToken", pushToken,
                "appVersion", "1.0.0", "deviceModel", "Pixel"));
    }

    /** Publish a domain event exactly as the outbox would. */
    private void publish(String eventType, String aggregateType, long aggregateId,
                         Map<String, Object> payload) throws Exception {
        relay.publish(new OutboxPublisher.DomainEventEnvelope(
                UUID.randomUUID().toString(), eventType, aggregateType, aggregateId, 1,
                json.writeValueAsString(payload), null, null, Instant.now()));
    }

    private JsonNode inbox(String token) throws Exception {
        return api.get(token, "/api/v1/notifications").at("/data");
    }

    // ── Who gets told ────────────────────────────────────────────────────

    @Nested
    @DisplayName("fan-out")
    class FanOut {

        @Test
        @DisplayName("an order event reaches the outlet's people")
        void reachesTheOutlet() throws Exception {
            var buyer = newBuyer();

            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1001L, Map.of(
                    "outletId", buyer.outletId(),
                    "orderNumber", "MP-1001",
                    "supplierName", "ABC Foods"));

            var received = inbox(buyer.token());
            assertThat(received.get("unreadCount").asLong()).isEqualTo(1);

            var notification = received.at("/notifications/0");
            assertThat(notification.get("title").asText()).isEqualTo("Order accepted");
            assertThat(notification.get("body").asText())
                    .isEqualTo("ABC Foods accepted order MP-1001.");
            // §23A.27 groups the inbox by category, and the server decides it.
            assertThat(notification.get("category").asText()).isEqualTo("ORDERS");
            // A route the app resolves, not a URL we built.
            assertThat(notification.get("targetType").asText()).isEqualTo("SUPPLIER_ORDER");
            assertThat(notification.get("targetId").asLong()).isEqualTo(1001L);
            assertThat(notification.get("critical").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("money reads as money, not as a database column")
        void formatsMoney() throws Exception {
            var buyer = newBuyer();

            publish("CreditApproved", "CREDIT_AGREEMENT", 1002L, Map.of(
                    "outletId", buyer.outletId(),
                    "approvedLimit", "35000.0000",
                    "creditPeriodDays", 30));

            var body = inbox(buyer.token()).at("/notifications/0/body").asText();

            // A DECIMAL(19,4) reaches the template as "35000.0000", and
            // "You have 35000.0000 of credit" is not a sentence to send anyone.
            assertThat(body).doesNotContain("35000.0000");
            assertThat(body).contains("35,000.00");
            // The period is a count, not money, and must not gain a currency symbol.
            assertThat(body).contains("30 days");
        }

        @Test
        @DisplayName("one event tells both sides, each in their own words")
        void bothSidesAreTold() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();

            publish("SupplierOrderExpired", "SUPPLIER_ORDER", 1002L, Map.of(
                    "outletId", buyer.outletId(),
                    "supplierStoreId", seller.storeId(),
                    "orderNumber", "MP-1002",
                    "supplierName", "ABC Foods"));

            String toBuyer = inbox(buyer.token()).at("/notifications/0/body").asText();
            String toSeller = inbox(seller.token()).at("/notifications/0/body").asText();

            assertThat(toBuyer).contains("didn't respond");
            assertThat(toSeller).contains("expired without an answer");
            // It means different things to each: one must re-source, the other has
            // lost the order.
            assertThat(toBuyer).isNotEqualTo(toSeller);
        }

        @Test
        @DisplayName("another restaurant is told nothing")
        void tenantsAreIsolated() throws Exception {
            var buyer = newBuyer();
            var stranger = newBuyer();

            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1003L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1003",
                    "supplierName", "ABC Foods"));

            assertThat(inbox(stranger.token()).get("unreadCount").asLong()).isZero();
            assertThat(inbox(stranger.token()).get("notifications")).isEmpty();
        }

        @Test
        @DisplayName("a redelivered event tells nobody twice")
        void relayIsIdempotent() throws Exception {
            var buyer = newBuyer();
            String eventId = UUID.randomUUID().toString();

            var envelope = new OutboxPublisher.DomainEventEnvelope(
                    eventId, "SupplierOrderAccepted", "SUPPLIER_ORDER", 1004L, 1,
                    json.writeValueAsString(Map.of("outletId", buyer.outletId(),
                            "orderNumber", "MP-1004", "supplierName", "ABC Foods")),
                    null, null, Instant.now());

            // The outbox is at-least-once. Telling someone twice is worse than not
            // at all — it reads as two orders.
            relay.publish(envelope);
            relay.publish(envelope);

            assertThat(inbox(buyer.token()).get("unreadCount").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("an event nobody has a rule for reaches nobody")
        void unmappedEventsAreIgnored() throws Exception {
            var buyer = newBuyer();

            // Doc 08 §1 lists forty events for the outbox; only some are inbox
            // items. A location update every few seconds would empty the app of
            // notifications within a day.
            publish("DeliveryLocationUpdated", "DELIVERY", 1005L,
                    Map.of("outletId", buyer.outletId()));

            assertThat(inbox(buyer.token()).get("unreadCount").asLong()).isZero();
        }
    }

    // ── Preferences ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("preferences")
    class Preferences {

        @Test
        @DisplayName("every category and channel comes back, including the defaults")
        void defaultsArePresent() throws Exception {
            var buyer = newBuyer();
            var preferences = api.get(buyer.token(), "/api/v1/notification-preferences")
                    .at("/data");

            // A settings screen should not have to invent the rows nobody has
            // changed. Six categories times two outbound channels.
            assertThat(preferences).hasSize(12);
            assertThat(preferences).allSatisfy(preference ->
                    assertThat(preference.get("enabled").asBoolean()).isTrue());
            // The inbox is not a preference: muting should silence a phone, not
            // erase the record that something happened.
            assertThat(preferences.toString()).doesNotContain("IN_APP");
        }

        @Test
        @DisplayName("muting a channel stops the push but keeps the inbox entry")
        void mutingStopsTheChannelNotTheRecord() throws Exception {
            var buyer = newBuyer();
            registerDevice(buyer.token(), "push-token-" + buyer.userId());

            api.patchStatus(buyer.token(), "/api/v1/notification-preferences", Map.of(
                    "preferences", List.of(Map.of(
                            "category", "ORDERS", "channel", "PUSH", "enabled", false))));

            // Not critical, so the mute applies.
            publish("SupplierOrderReady", "SUPPLIER_ORDER", 1006L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1006"));

            assertThat(inbox(buyer.token()).get("unreadCount").asLong())
                    .describedAs("the inbox entry is still made")
                    .isEqualTo(1);
            assertThat(pushCountFor(buyer.userId()))
                    .describedAs("but nothing is queued for the phone")
                    .isZero();
        }

        @Test
        @DisplayName("a critical notification ignores the mute")
        void criticalIgnoresPreferences() throws Exception {
            var buyer = newBuyer();
            registerDevice(buyer.token(), "push-token-" + buyer.userId());

            api.patchStatus(buyer.token(), "/api/v1/notification-preferences", Map.of(
                    "preferences", List.of(Map.of(
                            "category", "ORDERS", "channel", "PUSH", "enabled", false))));

            // Doc 08 §5. A supplier who muted order notifications still gets told an
            // order is waiting, because the alternative is an order that expires
            // beside a silent phone and a restaurant that gets nothing.
            publish("SupplierOrderRejected", "SUPPLIER_ORDER", 1007L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1007",
                    "supplierName", "ABC Foods"));

            assertThat(pushCountFor(buyer.userId())).isPositive();
        }

        private int pushCountFor(long userId) {
            return jdbc.queryForObject("""
                    select count(*) from notification_delivery d
                      join notification n on n.id = d.notification_id
                     where n.user_id = ? and d.channel = 'PUSH'
                    """, Integer.class, userId);
        }
    }

    // ── Getting it out ───────────────────────────────────────────────────

    @Nested
    @DisplayName("delivery")
    class Delivery {

        @Test
        @DisplayName("a queued push is sent and recorded")
        void pushIsSent() throws Exception {
            var buyer = newBuyer();
            registerDevice(buyer.token(), "push-good-" + buyer.userId());

            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1008L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1008",
                    "supplierName", "ABC Foods"));

            dispatcher.dispatch();

            var delivery = deliveryFor(buyer.userId(), "PUSH");
            assertThat(delivery.get("status")).isEqualTo("SENT");
            assertThat(delivery.get("provider")).isEqualTo("MOCK_PUSH");
            assertThat(delivery.get("provider_message_id")).isNotNull();
        }

        @Test
        @DisplayName("a dead token is failed permanently, not retried forever")
        void permanentFailureStopsRetrying() throws Exception {
            var buyer = newBuyer();
            // The mock refuses a token containing "invalid" — an app that was
            // uninstalled. Retrying it every minute for a day produces a queue full
            // of messages for phones that no longer exist.
            registerDevice(buyer.token(), "push-invalid-" + buyer.userId());

            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1009L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1009",
                    "supplierName", "ABC Foods"));

            dispatcher.dispatch();

            var delivery = deliveryFor(buyer.userId(), "PUSH");
            assertThat(delivery.get("status")).isEqualTo("FAILED");
            assertThat(delivery.get("next_attempt_at"))
                    .describedAs("no further attempt is scheduled")
                    .isNull();

            // And the inbox entry stands. Push is best-effort on top of the durable
            // channel; a dead token is not a reason to pretend nothing happened.
            assertThat(inbox(buyer.token()).get("unreadCount").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("a transient failure is retried with backoff")
        void transientFailureBacksOff() throws Exception {
            var buyer = newBuyer();
            registerDevice(buyer.token(), "push-flaky-" + buyer.userId());

            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1010L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1010",
                    "supplierName", "ABC Foods"));

            dispatcher.dispatch();

            var delivery = deliveryFor(buyer.userId(), "PUSH");
            assertThat(delivery.get("status")).isEqualTo("FAILED");
            assertThat(delivery.get("attempt_count")).isEqualTo(1);
            // Doc 08 §6: retry with bounded backoff. A provider down for a minute
            // must not be hit sixty times in that minute.
            assertThat(delivery.get("next_attempt_at")).isNotNull();
        }

        @Test
        @DisplayName("a user with no device gets the inbox entry and no failed push")
        void noDeviceIsNotAFailure() throws Exception {
            var buyer = newBuyer();

            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1011L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1011",
                    "supplierName", "ABC Foods"));

            assertThat(inbox(buyer.token()).get("unreadCount").asLong()).isEqualTo(1);
            // A delivery row would be a permanent failure against a phone that does
            // not exist, and would show up in every dashboard as a delivery problem.
            assertThat(jdbc.queryForObject("""
                    select count(*) from notification_delivery d
                      join notification n on n.id = d.notification_id
                     where n.user_id = ? and d.channel = 'PUSH'
                    """, Integer.class, buyer.userId())).isZero();
        }

        @Test
        @DisplayName("SMS goes out for the events that need acting on today")
        void smsForCriticalEvents() throws Exception {
            var buyer = newBuyer();

            publish("PaymentFailed", "PAYMENT", 1012L, Map.of(
                    "outletId", buyer.outletId(), "reason", "Card declined"));

            dispatcher.dispatch();

            var delivery = deliveryFor(buyer.userId(), "SMS");
            assertThat(delivery.get("status")).isEqualTo("SENT");
            assertThat(delivery.get("provider")).isEqualTo("MOCK_SMS");
        }

        private Map<String, Object> deliveryFor(long userId, String channel) {
            return jdbc.queryForMap("""
                    select d.status, d.provider, d.provider_message_id, d.attempt_count,
                           d.next_attempt_at
                      from notification_delivery d
                      join notification n on n.id = d.notification_id
                     where n.user_id = ? and d.channel = ?
                     order by d.id desc limit 1
                    """, userId, channel);
        }
    }

    // ── The inbox ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the inbox")
    class Inbox {

        @Test
        @DisplayName("marking read is server-backed and survives a reload")
        void markRead() throws Exception {
            var buyer = newBuyer();
            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1013L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1013",
                    "supplierName", "ABC Foods"));

            long id = inbox(buyer.token()).at("/notifications/0/id").asLong();
            api.post(buyer.token(), "/api/v1/notifications/" + id + "/read", Map.of());

            var after = inbox(buyer.token());
            assertThat(after.get("unreadCount").asLong()).isZero();
            assertThat(after.at("/notifications/0/read").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("mark-all-read clears the badge")
        void markAllRead() throws Exception {
            var buyer = newBuyer();
            for (int i = 0; i < 3; i++) {
                publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1100L + i, Map.of(
                        "outletId", buyer.outletId(), "orderNumber", "MP-" + i,
                        "supplierName", "ABC Foods"));
            }

            assertThat(inbox(buyer.token()).get("unreadCount").asLong()).isEqualTo(3);

            api.post(buyer.token(), "/api/v1/notifications/read-all", Map.of());

            assertThat(inbox(buyer.token()).get("unreadCount").asLong()).isZero();
        }

        @Test
        @DisplayName("you can't mark someone else's notification read")
        void cannotTouchAnotherInbox() throws Exception {
            var buyer = newBuyer();
            var stranger = newBuyer();
            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1014L, Map.of(
                    "outletId", buyer.outletId(), "orderNumber", "MP-1014",
                    "supplierName", "ABC Foods"));

            long id = inbox(buyer.token()).at("/notifications/0/id").asLong();

            // 404 rather than 403 (doc 09 §3) — an id from someone else's inbox
            // should not even confirm it exists.
            assertThat(api.postStatus(stranger.token(),
                    "/api/v1/notifications/" + id + "/read", Map.of())).isEqualTo(404);
            assertThat(inbox(buyer.token()).get("unreadCount").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("unread-only filters, and the badge still counts everything")
        void unreadOnly() throws Exception {
            var buyer = newBuyer();
            for (int i = 0; i < 2; i++) {
                publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 1200L + i, Map.of(
                        "outletId", buyer.outletId(), "orderNumber", "MP-" + i,
                        "supplierName", "ABC Foods"));
            }
            long id = inbox(buyer.token()).at("/notifications/0/id").asLong();
            api.post(buyer.token(), "/api/v1/notifications/" + id + "/read", Map.of());

            var unread = api.get(buyer.token(), "/api/v1/notifications?unreadOnly=true")
                    .at("/data");
            assertThat(unread.get("notifications")).hasSize(1);
            assertThat(unread.get("unreadCount").asLong()).isEqualTo(1);
        }
    }

    // ── Analytics ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("analytics")
    class Analytics {

        @Test
        @DisplayName("events are stored with their device time")
        void eventsAreStored() throws Exception {
            var buyer = newBuyer();

            var response = api.post(buyer.token(), "/api/v1/analytics/events", Map.of(
                    "events", List.of(Map.of(
                            "clientEventId", UUID.randomUUID().toString(),
                            "eventName", "checkout_started",
                            "outletId", buyer.outletId(),
                            "platform", "ANDROID",
                            "properties", Map.of("itemCount", 4),
                            "occurredAt", "2026-09-15T08:00:00Z")))).at("/data");

            assertThat(response.get("accepted").asInt()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select count(*) from analytics_event where user_id = ? and event_name = ?",
                    Integer.class, buyer.userId(), "checkout_started")).isEqualTo(1);
        }

        @Test
        @DisplayName("a resent batch is counted once")
        void ingestIsIdempotent() throws Exception {
            var buyer = newBuyer();
            String clientEventId = UUID.randomUUID().toString();
            Object batch = Map.of("events", List.of(Map.of(
                    "clientEventId", clientEventId,
                    "eventName", "product_viewed",
                    "occurredAt", "2026-09-15T08:00:00Z")));

            api.post(buyer.token(), "/api/v1/analytics/events", batch);
            var second = api.post(buyer.token(), "/api/v1/analytics/events", batch).at("/data");

            // A double-counted event quietly inflates every funnel metric doc 08 §9
            // is built from — and reporting the drop lets a client find its bug.
            assertThat(second.get("accepted").asInt()).isZero();
            assertThat(second.get("dropped").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("anything named like a secret is stripped before it is stored")
        void secretsAreStripped() throws Exception {
            var buyer = newBuyer();

            api.post(buyer.token(), "/api/v1/analytics/events", Map.of(
                    "events", List.of(Map.of(
                            "clientEventId", UUID.randomUUID().toString(),
                            "eventName", "payment_started",
                            "occurredAt", "2026-09-15T08:00:00Z",
                            "properties", Map.of(
                                    "amount", "4000.00",
                                    "otp", "483920",
                                    "cardNumber", "4111111111111111",
                                    "cvv", "123",
                                    "authToken", "sk_live_abc")))));

            String stored = jdbc.queryForObject(
                    "select properties from analytics_event where user_id = ? order by id desc "
                            + "limit 1", String.class, buyer.userId());

            // Doc 08 §8. The client is the wrong place to enforce this: one
            // debugging property added in a hurry and a card number is in a
            // database that was never meant to hold one.
            assertThat(stored).contains("amount")
                    .doesNotContain("483920")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("123456")
                    .doesNotContain("sk_live");
        }

        @Test
        @DisplayName("a nested object is dropped rather than stored whole")
        void nestedPropertiesAreDropped() throws Exception {
            var buyer = newBuyer();

            api.post(buyer.token(), "/api/v1/analytics/events", Map.of(
                    "events", List.of(Map.of(
                            "clientEventId", UUID.randomUUID().toString(),
                            "eventName", "cart_created",
                            "occurredAt", "2026-09-15T08:00:00Z",
                            "properties", Map.of(
                                    "itemCount", 3,
                                    "cart", Map.of("secretField", "should never land"))))));

            String stored = jdbc.queryForObject(
                    "select properties from analytics_event where user_id = ? order by id desc "
                            + "limit 1", String.class, buyer.userId());

            // An analytics table is the easiest place in a system to accidentally
            // keep an entire object graph, including the fields nobody audited.
            assertThat(stored).contains("itemCount").doesNotContain("should never land");
        }
    }
}
