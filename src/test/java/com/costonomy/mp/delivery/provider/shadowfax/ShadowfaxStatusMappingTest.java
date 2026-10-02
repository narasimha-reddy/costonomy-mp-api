package com.costonomy.mp.delivery.provider.shadowfax;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowfaxStatusMappingTest {

    @Test
    @DisplayName("maps Shadowfax statuses to ProviderDeliveryStatus")
    void mapsShadowfaxStatuses() {
        assertThat(ShadowfaxStatusMapper.map("new")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShadowfaxStatusMapper.map("assigned_for_delivery")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(ShadowfaxStatusMapper.map("received_from_client_warehouse")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(ShadowfaxStatusMapper.map("recd_at_fwd_hub")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(ShadowfaxStatusMapper.map("ofd")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(ShadowfaxStatusMapper.map("arrived_at_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(ShadowfaxStatusMapper.map("delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(ShadowfaxStatusMapper.map("cancelled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(ShadowfaxStatusMapper.map("failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("handles null, case variance, and unknown statuses gracefully")
    void handlesEdgeCases() {
        assertThat(ShadowfaxStatusMapper.map(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShadowfaxStatusMapper.map("  DELIVERED  ")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(ShadowfaxStatusMapper.map("unknown_custom_status")).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
