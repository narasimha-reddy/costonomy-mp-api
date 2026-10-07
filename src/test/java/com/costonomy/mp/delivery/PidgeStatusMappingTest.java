package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import com.costonomy.mp.delivery.provider.pidge.PidgeApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pidge's published fulfilment stages (their GET API notes) as our provider statuses. */
class PidgeStatusMappingTest {

    @Test
    @DisplayName("the stages map in the order they happen: assigned, at pickup, picked up, in transit, at drop, delivered")
    void mapsTheJourney() {
        assertThat(PidgeApiClient.mapPidgeStatus("CREATED")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(PidgeApiClient.mapPidgeStatus("OUT_FOR_PICKUP")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(PidgeApiClient.mapPidgeStatus("REACHED_PICKUP")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(PidgeApiClient.mapPidgeStatus("PICKED_UP")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(PidgeApiClient.mapPidgeStatus("IN_TRANSIT")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(PidgeApiClient.mapPidgeStatus("OUT_FOR_DELIVERY")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(PidgeApiClient.mapPidgeStatus("REACHED_DELIVERY"))
                .isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(PidgeApiClient.mapPidgeStatus("DELIVERED")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("a cancelled fulfilment frees the order for another rider; undelivered, returned, lost and damaged fail it")
    void mapsTheEndings() {
        assertThat(PidgeApiClient.mapPidgeStatus("CANCELLED")).isEqualTo(ProviderDeliveryStatus.DRIVER_CANCELLED);
        for (String stage : new String[] {"UNDELIVERED", "RTO_OUT_FOR_DELIVERY", "RTO_UNDELIVERED", "RTO_DELIVERED",
                "LOST", "DAMAGED", "DISPOSED"}) {
            assertThat(PidgeApiClient.mapPidgeStatus(stage)).describedAs(stage)
                    .isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        }
    }

    @Test
    @DisplayName("an unknown stage waits rather than moving the delivery")
    void unknownWaits() {
        assertThat(PidgeApiClient.mapPidgeStatus("SOMETHING_NEW")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(PidgeApiClient.mapPidgeStatus(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
