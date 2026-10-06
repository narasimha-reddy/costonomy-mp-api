package com.costonomy.mp.delivery.provider.porter;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PorterStatusMappingTest {

    @Test
    @DisplayName("maps Porter statuses to ProviderDeliveryStatus")
    void mapsPorterStatuses() {
        assertThat(PorterStatusMapper.map("created")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(PorterStatusMapper.map("allocating")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(PorterStatusMapper.map("searching_for_partner")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(PorterStatusMapper.map("assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(PorterStatusMapper.map("driver_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(PorterStatusMapper.map("driver_arrived")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(PorterStatusMapper.map("started")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(PorterStatusMapper.map("picked_up")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(PorterStatusMapper.map("in_transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(PorterStatusMapper.map("out_for_delivery")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(PorterStatusMapper.map("arrived_at_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(PorterStatusMapper.map("delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(PorterStatusMapper.map("completed")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(PorterStatusMapper.map("cancelled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(PorterStatusMapper.map("driver_cancelled")).isEqualTo(ProviderDeliveryStatus.DRIVER_CANCELLED);
        assertThat(PorterStatusMapper.map("pickup_failed")).isEqualTo(ProviderDeliveryStatus.PICKUP_FAILED);
        assertThat(PorterStatusMapper.map("failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("handles null, case variance, and unknown statuses gracefully")
    void handlesEdgeCases() {
        assertThat(PorterStatusMapper.map(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(PorterStatusMapper.map("  DELIVERED  ")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(PorterStatusMapper.map("unknown_custom_status")).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
