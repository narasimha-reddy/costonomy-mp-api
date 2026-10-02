package com.costonomy.mp.delivery.provider.shiprocket;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShiprocketStatusMappingTest {

    @Test
    @DisplayName("maps Shiprocket statuses to ProviderDeliveryStatus")
    void mapsShiprocketStatuses() {
        assertThat(ShiprocketStatusMapper.map("new")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShiprocketStatusMapper.map("order_created")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShiprocketStatusMapper.map("manifest_generated")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShiprocketStatusMapper.map("pickup_scheduled")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShiprocketStatusMapper.map("pickup_generated")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShiprocketStatusMapper.map("out_for_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(ShiprocketStatusMapper.map("pickup_queued")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(ShiprocketStatusMapper.map("assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(ShiprocketStatusMapper.map("reached_pickup_location")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(ShiprocketStatusMapper.map("driver_arrived")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(ShiprocketStatusMapper.map("picked_up")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(ShiprocketStatusMapper.map("pickup_done")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(ShiprocketStatusMapper.map("in_transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(ShiprocketStatusMapper.map("shipped")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(ShiprocketStatusMapper.map("reached_at_destination_hub")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(ShiprocketStatusMapper.map("arrived_at_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(ShiprocketStatusMapper.map("out_for_delivery")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(ShiprocketStatusMapper.map("delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(ShiprocketStatusMapper.map("canceled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(ShiprocketStatusMapper.map("cancelled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(ShiprocketStatusMapper.map("rto_initiated")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(ShiprocketStatusMapper.map("rto_delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(ShiprocketStatusMapper.map("lost")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(ShiprocketStatusMapper.map("damaged")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(ShiprocketStatusMapper.map("failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("handles null, case variance, spaces, and unknown statuses gracefully")
    void handlesEdgeCases() {
        assertThat(ShiprocketStatusMapper.map(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(ShiprocketStatusMapper.map("  DELIVERED  ")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(ShiprocketStatusMapper.map("PICKED UP")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(ShiprocketStatusMapper.map("OUT-FOR-DELIVERY")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(ShiprocketStatusMapper.map("unknown_custom_status")).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
