package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** A delivery event names who is carrying it, so the notification can say the right thing to the right side. */
@ExtendWith(MockitoExtension.class)
class DeliveryTimelineVariantTest {

    @Mock private DeliveryEventRepository events;
    @Mock private OutboxService outbox;

    private DeliveryTimeline timeline;

    @BeforeEach
    void setUp() {
        timeline = new DeliveryTimeline(events, outbox);
    }

    private static Delivery delivery(DeliveryMode mode) {
        var d = new Delivery();
        d.setId(7L);
        d.setSupplierOrderId(100L);
        d.setOutletId(10L);
        d.setSupplierStoreId(20L);
        d.setMode(mode);
        d.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
        return d;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedPayload() {
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("DriverAssigned"), eq("DELIVERY"), eq(7L), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("a partner delivery names the variant, and still carries the supplier's store so the supplier is reached")
    void partnerDeliveryNamesTheVariant() {
        timeline.publish(delivery(DeliveryMode.COSTONOMY), "DriverAssigned", null);

        var payload = publishedPayload();
        assertThat(payload).containsEntry("notificationVariant", "COSTONOMY_DELIVERY")
                .containsEntry("supplierStoreId", 20L)
                .containsEntry("supplierOrderId", 100L);
    }

    @Test
    @DisplayName("a supplier's own delivery names no variant")
    void ownDeliveryNamesNone() {
        timeline.publish(delivery(DeliveryMode.SUPPLIER_OWN), "DriverAssigned", null);

        assertThat(publishedPayload()).doesNotContainKey("notificationVariant");
    }
}
