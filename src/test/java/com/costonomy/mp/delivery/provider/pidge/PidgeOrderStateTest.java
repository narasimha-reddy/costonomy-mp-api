package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The order object as Pidge documents it (GET answer under "data", webhook as the root). */
class PidgeOrderStateTest {

    private final ObjectMapper json = new ObjectMapper();

    private String order(String parent, String stage, String logs) {
        return """
                {"id":"y-1","status":"%s","fulfillment":{"status":"%s","logs":[%s]}}
                """.formatted(parent, stage, logs);
    }

    private static final String CREATED = """
            {"timestamp":"2026-10-06T10:00:00.000Z","status":"CREATED"}""";
    private static final String OUT_FOR_PICKUP = """
            {"timestamp":"2026-10-06T10:02:00.000Z","status":"OUT_FOR_PICKUP","remark":"Start for Pickup",
             "location":{"latitude":17.44,"longitude":78.49},
             "rider":{"id":"306","name":"Preeti Punia","mobile":"8887772221"}}""";
    private static final String REACHED_PICKUP = """
            {"timestamp":"2026-10-06T10:08:00.000Z","status":"REACHED_PICKUP",
             "location":{"latitude":17.4399,"longitude":78.4983},
             "rider":{"id":"306","name":"Preeti Punia","mobile":"8887772221"}}""";

    @Test
    @DisplayName("fulfilled with a rider on the way is DRIVER_ASSIGNED, with the rider and where they were")
    void assigned() throws Exception {
        var state = PidgeOrderState.parse("y-1", json.readTree(order("fulfilled", "OUT_FOR_PICKUP",
                CREATED + "," + OUT_FOR_PICKUP)));

        assertThat(state.status()).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(state.riderName()).isEqualTo("Preeti Punia");
        assertThat(state.riderPhone()).isEqualTo("8887772221");
        assertThat(state.latitude()).isEqualByComparingTo("17.44");
        // The manifest entry says nothing happened yet, so it is not an event.
        assertThat(state.events()).hasSize(1);
        assertThat(state.events().get(0).status()).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
    }

    @Test
    @DisplayName("every stage is reported, newest first, so a delivery that skipped ahead walks them in order")
    void stagesNewestFirst() throws Exception {
        var state = PidgeOrderState.parse("y-1", json.readTree(order("fulfilled", "REACHED_PICKUP",
                CREATED + "," + OUT_FOR_PICKUP + "," + REACHED_PICKUP)));

        assertThat(state.status()).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(state.events()).extracting(e -> e.status()).containsExactly(
                ProviderDeliveryStatus.DRIVER_AT_PICKUP, ProviderDeliveryStatus.DRIVER_ASSIGNED);
        // Distinct and repeatable, so a replay is recognised.
        assertThat(state.events().get(0).providerEventId()).isEqualTo("pidge-y-1-REACHED_PICKUP-2026-10-06T10:08:00.000Z");
    }

    @Test
    @DisplayName("the parent status alone, 'fulfilled', does not mean a rider: a manifested order is still pending")
    void fulfilledWithoutRider() throws Exception {
        var state = PidgeOrderState.parse("y-1", json.readTree(order("fulfilled", "CREATED", CREATED)));

        assertThat(state.status()).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(state.events()).isEmpty();
        assertThat(state.riderName()).isNull();
    }

    @Test
    @DisplayName("cancelled and completed parents")
    void parents() throws Exception {
        assertThat(PidgeOrderState.parse("y-1", json.readTree("{\"id\":\"y-1\",\"status\":\"cancelled\"}")).status())
                .isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(PidgeOrderState.parse("y-1", json.readTree(order("completed", "DELIVERED", CREATED))).status())
                .isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(PidgeOrderState.parse("y-1", json.readTree(order("completed", "RTO_DELIVERED", CREATED))).status())
                .isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }
}
