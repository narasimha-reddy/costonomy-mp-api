package com.costonomy.mp.realtime;

import com.costonomy.mp.common.outbox.OutboxPublisher;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realtime end to end. Doc 06 §9, doc 05 §16, doc 09 §4.
 *
 * <p>Runs against a real HTTP port, because the thing under test is a WebSocket
 * handshake — MockMvc cannot upgrade a connection, and a test that exercised only
 * the services around the socket would leave the socket itself unverified.
 *
 * <p>Three properties carry this suite. <b>Channels are tenant isolation</b>: an
 * event reaches exactly the outlet and store it concerns and nobody else.
 * <b>The ticket is single-use</b>, because it travels in a query string.
 * And <b>all three transports agree</b> — what the socket pushes is what polling
 * returns, from the same cursor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RealtimeFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RealtimeEventRelayAccess relay;

    @LocalServerPort private int port;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private record Buyer(String token, long outletId, long restaurantId) {
    }

    private record Seller(String token, long storeId) {
    }

    // ── setup ────────────────────────────────────────────────────────────

    private Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "latitude", "17.4156", "longitude", "78.4347"))).get("data");
        return new Buyer(token, created.get("outlets").get(0).get("id").asLong(),
                created.get("id").asLong());
    }

    private Seller newSeller() throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "ABC Foods Pvt Ltd", "displayName", "ABC Foods",
                "firstStore", Map.of("name", "ABC store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        return new Seller(token, created.get("stores").get(0).get("id").asLong());
    }

    /**
     * Publish a domain event as the outbox would.
     *
     * <p>Straight into the relay rather than through a real order: this suite is
     * about the transport, and driving it with a checkout would test procurement
     * again and take ten times as long. The envelope is exactly what
     * {@code OutboxPublisher} emits.
     */
    private String publish(String eventType, String aggregateType, long aggregateId,
                           Map<String, Object> payload) throws Exception {
        String eventId = UUID.randomUUID().toString();
        relay.publish(new OutboxPublisher.DomainEventEnvelope(
                eventId, eventType, aggregateType, aggregateId, 1,
                json.writeValueAsString(payload), null, null, Instant.now()));
        return eventId;
    }

    private JsonNode ticketFor(String token) throws Exception {
        return api.post(token, "/api/v1/realtime/ticket", Map.of()).at("/data");
    }

    /** A connected socket, with every frame it received. */
    private static final class Socket implements AutoCloseable {
        private final WebSocketSession session;
        private final BlockingQueue<JsonNode> frames;

        private Socket(WebSocketSession session, BlockingQueue<JsonNode> frames) {
            this.session = session;
            this.frames = frames;
        }

        /** The next frame of a given type, or null if none arrives in time. */
        JsonNode await(String type, ObjectMapper json) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                var frame = frames.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                if (type == null || type.equals(frame.path("type").asText(null))
                        || (type.equals("event") && frame.has("eventType"))) {
                    return frame;
                }
            }
            return null;
        }

        /** Nothing arrived at all — used to prove an event did NOT leak. */
        boolean silentFor(long millis) throws InterruptedException {
            return frames.poll(millis, TimeUnit.MILLISECONDS) == null;
        }

        boolean isOpen() {
            return session.isOpen();
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
    }

    private Socket connect(String ticket) throws Exception {
        var frames = new LinkedBlockingQueue<JsonNode>();
        var client = new StandardWebSocketClient();

        var session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                try {
                    frames.add(json.readTree(message.getPayload()));
                } catch (Exception ignored) {
                    // A frame we cannot read is a failure the assertions will show.
                }
            }
        }, new WebSocketHttpHeaders(), socketUri(ticket)).get(10, TimeUnit.SECONDS);

        return new Socket(session, frames);
    }

    private URI socketUri(String ticket) {
        return URI.create("ws://localhost:" + port
                + "/costonomy-mp-api/api/v1/realtime/socket?ticket=" + ticket);
    }

    // ── The handshake ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getting connected")
    class Handshake {

        @Test
        @DisplayName("a ticket names the channels and where to resume")
        void ticketCarriesChannelsAndCursor() throws Exception {
            var buyer = newBuyer();
            var ticket = ticketFor(buyer.token());

            assertThat(ticket.get("ticket").asText()).isNotBlank();
            assertThat(ticket.get("url").asText()).isEqualTo("/api/v1/realtime/socket");
            // The client should not have to guess what it will be listening to,
            // nor be refused for asking.
            var channels = ticket.get("channels").findValuesAsText("");
            assertThat(ticket.get("channels").toString())
                    .contains("outlet:" + buyer.outletId());
            assertThat(ticket.get("cursor").isNull()).isFalse();
        }

        @Test
        @DisplayName("the ticket is stored hashed, never in the clear")
        void ticketIsStoredHashed() throws Exception {
            var buyer = newBuyer();
            String ticket = ticketFor(buyer.token()).get("ticket").asText();

            // Doc 09: the table must not be a list of working credentials.
            assertThat(jdbc.queryForObject(
                    "select count(*) from realtime_ticket where ticket_hash = ?",
                    Integer.class, ticket)).isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from realtime_ticket where ticket_hash = sha2(?, 256)",
                    Integer.class, ticket)).isEqualTo(1);
        }

        @Test
        @DisplayName("connecting says what you are listening to")
        void readyFrameOnConnect() throws Exception {
            var buyer = newBuyer();
            String ticket = ticketFor(buyer.token()).get("ticket").asText();

            try (var socket = connect(ticket)) {
                var ready = socket.await("ready", json);
                assertThat(ready).isNotNull();
                assertThat(ready.get("channels").toString())
                        .contains("outlet:" + buyer.outletId());
                // Doc 05 §16: the client refreshes authoritative state on connect,
                // and needs to know from where to play forward.
                assertThat(ready.get("cursor").isNull()).isFalse();
            }
        }

        @Test
        @DisplayName("a ticket works once")
        void ticketIsSingleUse() throws Exception {
            var buyer = newBuyer();
            String ticket = ticketFor(buyer.token()).get("ticket").asText();

            try (var first = connect(ticket)) {
                assertThat(first.await("ready", json)).isNotNull();

                // It travels in a query string, so it must be spent on use — a
                // replay from a log must not open a second socket.
                assertThatConnectionIsRefused(ticket);
            }
        }

        @Test
        @DisplayName("an invented ticket is refused")
        void forgedTicketIsRefused() throws Exception {
            assertThatConnectionIsRefused("not-a-real-ticket");
        }

        @Test
        @DisplayName("an expired ticket is refused")
        void expiredTicketIsRefused() throws Exception {
            var buyer = newBuyer();
            String ticket = ticketFor(buyer.token()).get("ticket").asText();

            jdbc.update("update realtime_ticket set expires_at = "
                    + "date_sub(utc_timestamp(6), interval 1 minute) "
                    + "where ticket_hash = sha2(?, 256)", ticket);

            assertThatConnectionIsRefused(ticket);
        }

        @Test
        @DisplayName("a user with no grants gets no ticket")
        void noGrantsNoTicket() throws Exception {
            String token = api.loginFresh();

            // A socket that can never carry a message looks to a client exactly
            // like one that is broken, so it is refused rather than issued.
            assertThat(api.postStatus(token, "/api/v1/realtime/ticket", Map.of()))
                    .isEqualTo(403);
        }

        private void assertThatConnectionIsRefused(String ticket) {
            try (var socket = connect(ticket)) {
                // Some stacks complete the upgrade and close immediately; either
                // way nothing may be delivered.
                assertThat(socket.await("ready", json))
                        .describedAs("a refused handshake must not become a session")
                        .isNull();
            } catch (Exception expected) {
                // The handshake was rejected outright, which is the other correct
                // shape of this answer.
            }
        }
    }

    // ── Delivery and isolation ───────────────────────────────────────────

    @Nested
    @DisplayName("who receives what")
    class Isolation {

        @Test
        @DisplayName("an event arrives on the socket of the outlet it concerns")
        void eventReachesItsOutlet() throws Exception {
            var buyer = newBuyer();
            String ticket = ticketFor(buyer.token()).get("ticket").asText();

            try (var socket = connect(ticket)) {
                assertThat(socket.await("ready", json)).isNotNull();

                publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 4242L,
                        Map.of("outletId", buyer.outletId(), "orderNumber", "MP-4242"));

                var event = socket.await("event", json);
                assertThat(event).isNotNull();
                assertThat(event.get("eventType").asText()).isEqualTo("SupplierOrderAccepted");
                assertThat(event.get("channel").asText())
                        .isEqualTo("outlet:" + buyer.outletId());
                assertThat(event.get("aggregateId").asLong()).isEqualTo(4242L);
                // The cursor is on every frame, so a client can resume from the
                // last thing it actually saw rather than from when it connected.
                assertThat(event.get("cursor").asLong()).isPositive();
            }
        }

        @Test
        @DisplayName("an order reaches both sides, each on their own channel")
        void bothSidesReceiveTheSameOrder() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();

            try (var buyerSocket = connect(ticketFor(buyer.token()).get("ticket").asText());
                 var sellerSocket = connect(ticketFor(seller.token()).get("ticket").asText())) {

                assertThat(buyerSocket.await("ready", json)).isNotNull();
                assertThat(sellerSocket.await("ready", json)).isNotNull();

                publish("SupplierOrderReleased", "SUPPLIER_ORDER", 77L, Map.of(
                        "outletId", buyer.outletId(),
                        "supplierStoreId", seller.storeId(),
                        "orderNumber", "MP-77"));

                var toBuyer = buyerSocket.await("event", json);
                var toSeller = sellerSocket.await("event", json);

                assertThat(toBuyer).isNotNull();
                assertThat(toSeller).isNotNull();
                assertThat(toBuyer.get("channel").asText())
                        .isEqualTo("outlet:" + buyer.outletId());
                assertThat(toSeller.get("channel").asText())
                        .isEqualTo("supplier-store:" + seller.storeId());
                // Same event, two channels — which is why the projection is per
                // (event, channel) rather than per event.
                assertThat(toBuyer.get("aggregateId").asLong())
                        .isEqualTo(toSeller.get("aggregateId").asLong());
            }
        }

        @Test
        @DisplayName("one restaurant never hears about another's order")
        void anotherTenantHearsNothing() throws Exception {
            var buyer = newBuyer();
            var stranger = newBuyer();

            try (var strangerSocket =
                         connect(ticketFor(stranger.token()).get("ticket").asText())) {

                assertThat(strangerSocket.await("ready", json)).isNotNull();

                publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 99L,
                        Map.of("outletId", buyer.outletId(), "orderNumber", "MP-99"));

                // The whole point of channels. A leak here has no per-message audit
                // trail to find it afterwards.
                assertThat(strangerSocket.silentFor(1500))
                        .describedAs("another tenant's event must not arrive")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a restaurant-wide grant covers its outlets")
        void grantsExpandDownTheHierarchy() throws Exception {
            var buyer = newBuyer();
            var ticket = ticketFor(buyer.token());

            // The owner's grant is at RESTAURANT scope, and the event is addressed
            // to an outlet. Realtime must agree with ScopeType.satisfyingScopes(),
            // or a restaurant owner would see nothing on their own account.
            assertThat(ticket.get("channels").toString())
                    .contains("outlet:" + buyer.outletId());
        }
    }

    // ── The fallback, and agreement between transports ───────────────────

    @Nested
    @DisplayName("polling fallback")
    class Polling {

        @Test
        @DisplayName("polling returns what the socket would have pushed")
        void pollingSeesTheSameEvents() throws Exception {
            var buyer = newBuyer();
            long cursor = api.get(buyer.token(), "/api/v1/realtime/events")
                    .at("/data/cursor").asLong();

            publish("SupplierOrderReleased", "SUPPLIER_ORDER", 11L,
                    Map.of("outletId", buyer.outletId(), "orderNumber", "MP-11"));
            publish("DeliveryDRIVER_ASSIGNED", "DELIVERY", 12L,
                    Map.of("outletId", buyer.outletId(), "status", "DRIVER_ASSIGNED"));

            var page = api.get(buyer.token(), "/api/v1/realtime/events?cursor=" + cursor)
                    .at("/data");

            assertThat(page.get("events")).hasSize(2);
            assertThat(page.get("events").get(0).get("eventType").asText())
                    .isEqualTo("SupplierOrderReleased");
            // Ordered by cursor, so a poller sees them in the order they happened.
            assertThat(page.get("cursor").asLong()).isGreaterThan(cursor);
        }

        @Test
        @DisplayName("the cursor advances, so a quiet channel isn't re-read forever")
        void cursorAdvances() throws Exception {
            var buyer = newBuyer();
            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 21L,
                    Map.of("outletId", buyer.outletId()));

            var first = api.get(buyer.token(), "/api/v1/realtime/events?cursor=0").at("/data");
            long cursor = first.get("cursor").asLong();
            assertThat(first.get("events")).isNotEmpty();

            var second = api.get(buyer.token(),
                    "/api/v1/realtime/events?cursor=" + cursor).at("/data");
            assertThat(second.get("events")).isEmpty();
            assertThat(second.get("cursor").asLong()).isEqualTo(cursor);
        }

        @Test
        @DisplayName("a full page says to come straight back")
        void hasMoreWhenCapped() throws Exception {
            var buyer = newBuyer();
            for (int i = 0; i < 3; i++) {
                publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 30L + i,
                        Map.of("outletId", buyer.outletId()));
            }

            var page = api.get(buyer.token(),
                    "/api/v1/realtime/events?cursor=0&limit=2").at("/data");

            assertThat(page.get("events")).hasSize(2);
            // Otherwise a client behind by a thousand events would take a thousand
            // polling intervals to catch up.
            assertThat(page.get("hasMore").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("a client can only poll its own channels")
        void pollingIsScopedToGrants() throws Exception {
            var buyer = newBuyer();
            var stranger = newBuyer();

            publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 41L,
                    Map.of("outletId", buyer.outletId()));

            // There is no channel parameter to abuse: what a caller may see is the
            // server's decision, derived from grants.
            var page = api.get(stranger.token(), "/api/v1/realtime/events?cursor=0").at("/data");
            assertThat(page.get("events")).isEmpty();
        }

        @Test
        @DisplayName("a reconnecting client resumes exactly where it stopped")
        void reconnectResumesFromCursor() throws Exception {
            var buyer = newBuyer();
            String ticket = ticketFor(buyer.token()).get("ticket").asText();

            long cursor;
            try (var socket = connect(ticket)) {
                var ready = socket.await("ready", json);
                cursor = ready.get("cursor").asLong();

                publish("SupplierOrderAccepted", "SUPPLIER_ORDER", 51L,
                        Map.of("outletId", buyer.outletId()));
                var seen = socket.await("event", json);
                cursor = seen.get("cursor").asLong();
            }

            // Disconnected. Two events happen while the client is away.
            publish("SupplierOrderPreparing", "SUPPLIER_ORDER", 52L,
                    Map.of("outletId", buyer.outletId()));
            publish("SupplierOrderReady", "SUPPLIER_ORDER", 53L,
                    Map.of("outletId", buyer.outletId()));

            // Doc 05 §16: the client catches up over REST from its last cursor —
            // the same rows, in the same shape, as the socket would have pushed.
            var missed = api.get(buyer.token(),
                    "/api/v1/realtime/events?cursor=" + cursor).at("/data");

            assertThat(missed.get("events")).hasSize(2);
            assertThat(missed.get("events").get(0).get("eventType").asText())
                    .isEqualTo("SupplierOrderPreparing");
            assertThat(missed.get("events").get(1).get("eventType").asText())
                    .isEqualTo("SupplierOrderReady");
        }
    }

    // ── The projection ───────────────────────────────────────────────────

    @Nested
    @DisplayName("the projection")
    class Projection {

        @Test
        @DisplayName("a redelivered outbox event is projected once")
        void relayIsIdempotent() throws Exception {
            var buyer = newBuyer();
            String eventId = UUID.randomUUID().toString();

            var envelope = new OutboxPublisher.DomainEventEnvelope(
                    eventId, "SupplierOrderAccepted", "SUPPLIER_ORDER", 61L, 1,
                    json.writeValueAsString(Map.of("outletId", buyer.outletId())),
                    null, null, Instant.now());

            // The outbox is at-least-once, so it will re-offer events it has
            // already delivered. A second projection would show the restaurant the
            // same order twice.
            relay.publish(envelope);
            relay.publish(envelope);

            assertThat(jdbc.queryForObject(
                    "select count(*) from realtime_event where event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
        }

        @Test
        @DisplayName("an event nobody is entitled to is never projected")
        void unroutableEventsAreNotStored() throws Exception {
            String eventId = publish("CatalogImportCompleted", "CATALOG_IMPORT", 71L,
                    Map.of("rows", 400));

            assertThat(jdbc.queryForObject(
                    "select count(*) from realtime_event where event_id = ?",
                    Integer.class, eventId)).isZero();
        }
    }
}
