package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PidgeStatusMappingTest {

    @Test
    @DisplayName("pidge status strings map accurately to ProviderDeliveryStatus")
    void mapsStatuses() {
        assertThat(PidgeApiClient.mapPidgeStatus("ORDER_CREATED"))
                .isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PENDING);
        assertThat(PidgeApiClient.mapPidgeStatus("RIDER_ASSIGNED"))
                .isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(PidgeApiClient.mapPidgeStatus("REACHED_PICKUP"))
                .isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(PidgeApiClient.mapPidgeStatus("OUT_FOR_DELIVERY"))
                .isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        assertThat(PidgeApiClient.mapPidgeStatus("DELIVERED"))
                .isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(PidgeApiClient.mapPidgeStatus("RIDER_CANCELLED"))
                .isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED);
        assertThat(PidgeApiClient.mapPidgeStatus("DELIVERY_FAILED"))
                .isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED);
    }
}
