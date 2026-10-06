package com.costonomy.mp.delivery.provider.delhivery;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelhiveryStatusMappingTest {

    @Test
    @DisplayName("maps Delhivery statuses to ProviderDeliveryStatus")
    void mapsDelhiveryStatuses() {
        assertThat(DelhiveryStatusMapper.map("manifested")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(DelhiveryStatusMapper.map("pre_transit")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(DelhiveryStatusMapper.map("order_created")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(DelhiveryStatusMapper.map("pending")).isEqualTo(ProviderDeliveryStatus.PENDING);

        assertThat(DelhiveryStatusMapper.map("pickup_scheduled")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(DelhiveryStatusMapper.map("pickup_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(DelhiveryStatusMapper.map("assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(DelhiveryStatusMapper.map("driver_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);

        assertThat(DelhiveryStatusMapper.map("reached_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(DelhiveryStatusMapper.map("at_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);

        assertThat(DelhiveryStatusMapper.map("picked_up")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(DelhiveryStatusMapper.map("picked")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(DelhiveryStatusMapper.map("pickup_done")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);

        assertThat(DelhiveryStatusMapper.map("in_transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(DelhiveryStatusMapper.map("dispatched")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(DelhiveryStatusMapper.map("transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);

        assertThat(DelhiveryStatusMapper.map("out_for_delivery")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(DelhiveryStatusMapper.map("reached_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(DelhiveryStatusMapper.map("arrived_at_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);

        assertThat(DelhiveryStatusMapper.map("delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(DelhiveryStatusMapper.map("closed")).isEqualTo(ProviderDeliveryStatus.DELIVERED);

        assertThat(DelhiveryStatusMapper.map("cancelled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(DelhiveryStatusMapper.map("canceled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);

        assertThat(DelhiveryStatusMapper.map("rto")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(DelhiveryStatusMapper.map("rto_initiated")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(DelhiveryStatusMapper.map("returned")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(DelhiveryStatusMapper.map("failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(DelhiveryStatusMapper.map("undelivered")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(DelhiveryStatusMapper.map("lost")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(DelhiveryStatusMapper.map("damaged")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("handles null, case variance, spaces, and unknown statuses gracefully")
    void handlesEdgeCases() {
        assertThat(DelhiveryStatusMapper.map(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(DelhiveryStatusMapper.map("  DELIVERED  ")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(DelhiveryStatusMapper.map("PICKED UP")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(DelhiveryStatusMapper.map("OUT-FOR-DELIVERY")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(DelhiveryStatusMapper.map("IN TRANSIT")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(DelhiveryStatusMapper.map("unknown_custom_status")).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
