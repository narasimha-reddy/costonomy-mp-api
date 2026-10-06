package com.costonomy.mp.delivery.provider.loadshare;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadshareStatusMappingTest {

    @Test
    @DisplayName("maps LoadShare statuses to ProviderDeliveryStatus")
    void mapsLoadshareStatuses() {
        assertThat(LoadshareStatusMapper.map("created")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(LoadshareStatusMapper.map("pending")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(LoadshareStatusMapper.map("accepted")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(LoadshareStatusMapper.map("unassigned")).isEqualTo(ProviderDeliveryStatus.PENDING);

        assertThat(LoadshareStatusMapper.map("assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(LoadshareStatusMapper.map("allocated")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(LoadshareStatusMapper.map("rider_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);

        assertThat(LoadshareStatusMapper.map("arrived_at_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(LoadshareStatusMapper.map("at_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(LoadshareStatusMapper.map("reached_store")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);

        assertThat(LoadshareStatusMapper.map("picked_up")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(LoadshareStatusMapper.map("pickup_complete")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);

        assertThat(LoadshareStatusMapper.map("in_transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(LoadshareStatusMapper.map("out_for_delivery")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(LoadshareStatusMapper.map("dispatched")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);

        assertThat(LoadshareStatusMapper.map("reached_drop")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(LoadshareStatusMapper.map("arrived_at_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(LoadshareStatusMapper.map("at_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);

        assertThat(LoadshareStatusMapper.map("delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(LoadshareStatusMapper.map("completed")).isEqualTo(ProviderDeliveryStatus.DELIVERED);

        assertThat(LoadshareStatusMapper.map("cancelled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(LoadshareStatusMapper.map("canceled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);

        assertThat(LoadshareStatusMapper.map("failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(LoadshareStatusMapper.map("returned")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(LoadshareStatusMapper.map("rto")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(LoadshareStatusMapper.map("pickup_failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("handles null, case variance, spaces, and unknown statuses gracefully")
    void handlesEdgeCases() {
        assertThat(LoadshareStatusMapper.map(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(LoadshareStatusMapper.map("  DELIVERED  ")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(LoadshareStatusMapper.map("PICKED UP")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(LoadshareStatusMapper.map("OUT-FOR-DELIVERY")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(LoadshareStatusMapper.map("ARRIVED AT PICKUP")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(LoadshareStatusMapper.map("unknown_custom_status")).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
