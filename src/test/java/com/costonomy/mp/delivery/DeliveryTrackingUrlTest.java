package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryTrackingUrlTest {

    @Test
    void deliveryEntityAndDtoExposeTrackingUrl() {
        var delivery = new Delivery();
        delivery.setTrackingUrl("https://track.pidge.in/order/pidge-test-123");
        delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
        delivery.setVehicleType(VehicleType.TWO_WHEELER);

        assertThat(delivery.getTrackingUrl()).isEqualTo("https://track.pidge.in/order/pidge-test-123");

        var response = new DeliveryDtos.DeliveryResponse(
                1L, 2L, "MP-101", DeliveryMode.COSTONOMY, DeliveryStatus.DRIVER_ASSIGNED,
                BigDecimal.valueOf(50), "INR", "Pickup St", "Drop St",
                "Rider Bob", "+919876543210", "MH-01-AB-1234",
                25, Instant.now().plusSeconds(1500),
                true,
                delivery.getTrackingUrl(),
                null, false, null,
                null, null, Instant.now(), null, null,
                BigDecimal.valueOf(5.5), BigDecimal.valueOf(0.02), "TWO_WHEELER",
                List.of(), null, null, false);

        assertThat(response.trackingUrl()).isEqualTo("https://track.pidge.in/order/pidge-test-123");
        assertThat(response.trackable()).isTrue();
    }
}
