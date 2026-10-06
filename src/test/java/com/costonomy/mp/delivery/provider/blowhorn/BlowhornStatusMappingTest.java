package com.costonomy.mp.delivery.provider.blowhorn;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlowhornStatusMappingTest {

    @Test
    @DisplayName("maps Blowhorn statuses to ProviderDeliveryStatus")
    void mapsBlowhornStatuses() {
        assertThat(BlowhornStatusMapper.map("created")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BlowhornStatusMapper.map("pending")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BlowhornStatusMapper.map("accepted")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BlowhornStatusMapper.map("booked")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BlowhornStatusMapper.map("manifest_created")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BlowhornStatusMapper.map("order_created")).isEqualTo(ProviderDeliveryStatus.PENDING);

        assertThat(BlowhornStatusMapper.map("assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(BlowhornStatusMapper.map("driver_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(BlowhornStatusMapper.map("pilot_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(BlowhornStatusMapper.map("allocated")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);

        assertThat(BlowhornStatusMapper.map("arrived_at_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(BlowhornStatusMapper.map("reached_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(BlowhornStatusMapper.map("at_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);

        assertThat(BlowhornStatusMapper.map("picked_up")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(BlowhornStatusMapper.map("pickup_done")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);

        assertThat(BlowhornStatusMapper.map("in_transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(BlowhornStatusMapper.map("out_for_delivery")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(BlowhornStatusMapper.map("dispatched")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);

        assertThat(BlowhornStatusMapper.map("reached_drop")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(BlowhornStatusMapper.map("arrived_at_drop")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(BlowhornStatusMapper.map("reached_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);

        assertThat(BlowhornStatusMapper.map("delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(BlowhornStatusMapper.map("completed")).isEqualTo(ProviderDeliveryStatus.DELIVERED);

        assertThat(BlowhornStatusMapper.map("cancelled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(BlowhornStatusMapper.map("canceled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);

        assertThat(BlowhornStatusMapper.map("failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(BlowhornStatusMapper.map("returned")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(BlowhornStatusMapper.map("rto")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(BlowhornStatusMapper.map("undelivered")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(BlowhornStatusMapper.map("pickup_failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("handles null, case variance, spaces, and unknown statuses gracefully")
    void handlesEdgeCases() {
        assertThat(BlowhornStatusMapper.map(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(BlowhornStatusMapper.map("  DELIVERED  ")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(BlowhornStatusMapper.map("PICKED UP")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(BlowhornStatusMapper.map("OUT-FOR-DELIVERY")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(BlowhornStatusMapper.map("PILOT ASSIGNED")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(BlowhornStatusMapper.map("unknown_custom_status")).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
