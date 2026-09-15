package com.costonomy.mp.realtime;

import com.costonomy.mp.realtime.domain.RealtimeChannel;
import com.costonomy.mp.realtime.service.RealtimeRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Channel naming and event routing. Doc 06 §9.
 *
 * <p>Routing is an authorization decision wearing a different hat: a channel is
 * who may see an event, so a routing mistake is a disclosure rather than a missing
 * notification. These are the cases where a permissive default would be tempting.
 */
class RealtimeRoutingTest {

    private final RealtimeRouter router = new RealtimeRouter(new ObjectMapper());

    @Nested
    @DisplayName("channel names")
    class Names {

        @Test
        @DisplayName("a channel round-trips through its wire form")
        void roundTrip() {
            var channel = RealtimeChannel.outlet(12L);
            assertThat(channel.name()).isEqualTo("outlet:12");
            assertThat(RealtimeChannel.parse("outlet:12")).isEqualTo(channel);
            assertThat(RealtimeChannel.parse("supplier-store:7"))
                    .isEqualTo(RealtimeChannel.supplierStore(7L));
        }

        @Test
        @DisplayName("anything malformed is refused, not guessed at")
        void malformedIsRefused() {
            // A guess here is an authorization decision made on a bad string.
            for (String bad : new String[] {
                    null, "", "outlet", "outlet:", ":12", "outlet:abc",
                    "unknown:12", "outlet:12:extra", "OUTLET:12"}) {
                assertThat(RealtimeChannel.parse(bad))
                        .describedAs("parse(%s)", bad).isNull();
            }
        }

        @Test
        @DisplayName("the two channel types cannot collide")
        void typesAreDistinct() {
            // Same id, different tenant. If these ever produced the same name, a
            // supplier store would receive an outlet's events.
            assertThat(RealtimeChannel.outlet(5L).name())
                    .isNotEqualTo(RealtimeChannel.supplierStore(5L).name());
        }
    }

    @Nested
    @DisplayName("routing an event")
    class Routing {

        @Test
        @DisplayName("an order reaches both the restaurant and the store")
        void bothSidesOfAnOrder() {
            // A supplier order concerns two tenants, which is why the projection is
            // per (event, channel) rather than per event.
            var channels = router.channelsFor("SUPPLIER_ORDER",
                    """
                    {"orderNumber":"MP-1","outletId":12,"supplierStoreId":7}
                    """);

            assertThat(channels).containsExactlyInAnyOrder(
                    RealtimeChannel.outlet(12L), RealtimeChannel.supplierStore(7L));
        }

        @Test
        @DisplayName("an event with one party reaches one channel")
        void oneSided() {
            var channels = router.channelsFor("CREDIT_AGREEMENT",
                    "{\"outletId\":12,\"approvedLimit\":\"200000\"}");

            assertThat(channels).containsExactly(RealtimeChannel.outlet(12L));
        }

        @Test
        @DisplayName("an event about nobody goes nowhere")
        void unroutableIsDropped() {
            // Plenty of domain events are nobody's business in realtime. Dropping
            // them is the safe answer; routing on a guess would put an event on a
            // channel by accident, and on this transport an accident is a leak.
            assertThat(router.channelsFor("CATALOG_IMPORT", "{\"rows\":42}")).isEmpty();
            assertThat(router.channelsFor("SETTLEMENT", "{}")).isEmpty();
        }

        @Test
        @DisplayName("an unreadable payload routes nowhere rather than throwing")
        void badPayloadIsSurvivable() {
            // The relay runs inside the outbox drain. Throwing here would stall
            // every later event behind one malformed row.
            assertThat(router.channelsFor("SUPPLIER_ORDER", "not json at all")).isEmpty();
            assertThat(router.channelsFor("SUPPLIER_ORDER", null)).isEmpty();
        }

        @Test
        @DisplayName("ids sent as strings still route")
        void stringIdsAreAccepted() {
            // Payloads are built by hand across five modules; some carry numbers as
            // strings. Silently failing to route would mean an event that never
            // arrives and nothing that says why.
            var channels = router.channelsFor("DELIVERY",
                    "{\"outletId\":\"12\",\"supplierStoreId\":\"7\"}");

            assertThat(channels).containsExactlyInAnyOrder(
                    RealtimeChannel.outlet(12L), RealtimeChannel.supplierStore(7L));
        }

        @Test
        @DisplayName("a null id is not a channel zero")
        void nullIdsAreIgnored() {
            var channels = router.channelsFor("SUPPLIER_ORDER",
                    "{\"outletId\":null,\"supplierStoreId\":7}");

            assertThat(channels).containsExactly(RealtimeChannel.supplierStore(7L));
        }
    }
}
