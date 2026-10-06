package com.costonomy.mp.delivery.provider.xpressbees;

import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XpressbeesStatusMappingTest {

    @Test
    @DisplayName("maps Xpressbees statuses to ProviderDeliveryStatus")
    void mapsXpressbeesStatuses() {
        assertThat(XpressbeesStatusMapper.map("manifested")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(XpressbeesStatusMapper.map("booked")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(XpressbeesStatusMapper.map("order_created")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(XpressbeesStatusMapper.map("pending")).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(XpressbeesStatusMapper.map("open")).isEqualTo(ProviderDeliveryStatus.PENDING);

        assertThat(XpressbeesStatusMapper.map("pickup_scheduled")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(XpressbeesStatusMapper.map("pickup_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(XpressbeesStatusMapper.map("assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(XpressbeesStatusMapper.map("driver_assigned")).isEqualTo(ProviderDeliveryStatus.DRIVER_ASSIGNED);

        assertThat(XpressbeesStatusMapper.map("reached_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);
        assertThat(XpressbeesStatusMapper.map("at_pickup")).isEqualTo(ProviderDeliveryStatus.DRIVER_AT_PICKUP);

        assertThat(XpressbeesStatusMapper.map("picked_up")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(XpressbeesStatusMapper.map("picked")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(XpressbeesStatusMapper.map("pickup_done")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);

        assertThat(XpressbeesStatusMapper.map("in_transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(XpressbeesStatusMapper.map("dispatched")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(XpressbeesStatusMapper.map("transit")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);

        assertThat(XpressbeesStatusMapper.map("out_for_delivery")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(XpressbeesStatusMapper.map("reached_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(XpressbeesStatusMapper.map("arrived_at_destination")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);

        assertThat(XpressbeesStatusMapper.map("delivered")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(XpressbeesStatusMapper.map("completed")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(XpressbeesStatusMapper.map("closed")).isEqualTo(ProviderDeliveryStatus.DELIVERED);

        assertThat(XpressbeesStatusMapper.map("cancelled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);
        assertThat(XpressbeesStatusMapper.map("canceled")).isEqualTo(ProviderDeliveryStatus.CANCELLED);

        assertThat(XpressbeesStatusMapper.map("rto")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(XpressbeesStatusMapper.map("rto_initiated")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(XpressbeesStatusMapper.map("returned")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(XpressbeesStatusMapper.map("failed")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(XpressbeesStatusMapper.map("undelivered")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(XpressbeesStatusMapper.map("lost")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
        assertThat(XpressbeesStatusMapper.map("damaged")).isEqualTo(ProviderDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("handles null, case variance, spaces, and unknown statuses gracefully")
    void handlesEdgeCases() {
        assertThat(XpressbeesStatusMapper.map(null)).isEqualTo(ProviderDeliveryStatus.PENDING);
        assertThat(XpressbeesStatusMapper.map("  DELIVERED  ")).isEqualTo(ProviderDeliveryStatus.DELIVERED);
        assertThat(XpressbeesStatusMapper.map("PICKED UP")).isEqualTo(ProviderDeliveryStatus.PICKED_UP);
        assertThat(XpressbeesStatusMapper.map("OUT-FOR-DELIVERY")).isEqualTo(ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(XpressbeesStatusMapper.map("IN TRANSIT")).isEqualTo(ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(XpressbeesStatusMapper.map("unknown_custom_status")).isEqualTo(ProviderDeliveryStatus.PENDING);
    }
}
