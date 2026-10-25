package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryEventService;
import com.costonomy.mp.delivery.service.DeliveryTimeline;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * A provider event whose apply fails must not be remembered as applied (D-204 audit, gap 10): the provider's retry has
 * to be applied, not refused as a duplicate. The event row and the status change commit together or not at all.
 */
@AutoConfigureMockMvc
class DeliveryEventRetryIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private DeliveryEventService events;
    @Autowired private DeliveryRepository deliveries;
    @SpyBean private DeliveryTimeline timeline;

    private DeliveryFixture.Setup s;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(timeline);
        s = DeliveryFixture.readyForPickup(mvc, json, new ApiClient(mvc, json), jdbc, paymentProvider, paymentJobs, "0.00", "0.00");
        jdbc.update("update delivery set status = 'DRIVER_ASSIGNED', provider_code = 'MOCK_EXPRESS', "
                + "provider_delivery_id = 'evt-1' where id = ?", s.deliveryId());
    }

    private DeliveryProvider.ProviderEvent pickedUp(String id) {
        return new DeliveryProvider.ProviderEvent(id, DeliveryProvider.ProviderDeliveryStatus.PICKED_UP, "Picked up", Instant.now());
    }

    private String status() {
        return jdbc.queryForObject("select status from delivery where id = ?", String.class, s.deliveryId());
    }

    private int storedEvents(String providerEventId) {
        return jdbc.queryForObject("select count(*) from delivery_event where delivery_id = ? and provider_event_id = ?",
                Integer.class, s.deliveryId(), providerEventId);
    }

    @Test
    @DisplayName("a provider event whose apply failed leaves nothing behind, and its retry is applied, once")
    void aFailedApplyIsRetriedNotRefusedAsADuplicate() {
        Mockito.doThrow(new IllegalStateException("boom")).doCallRealMethod()
                .when(timeline).publish(any(), anyString(), any());

        assertThatThrownBy(() -> events.apply(deliveries.findById(s.deliveryId()).orElseThrow(), pickedUp("pk-1")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(status()).as("the failed apply changed nothing").isEqualTo("DRIVER_ASSIGNED");
        assertThat(storedEvents("pk-1")).as("and was not remembered as applied").isZero();

        String retry = events.apply(deliveries.findById(s.deliveryId()).orElseThrow(), pickedUp("pk-1"));

        assertThat(retry).isEqualTo("APPLIED");
        assertThat(status()).isEqualTo("PICKED_UP");
        assertThat(storedEvents("pk-1")).isEqualTo(1);

        String again = events.apply(deliveries.findById(s.deliveryId()).orElseThrow(), pickedUp("pk-1"));
        assertThat(again).as("a third delivery of the same event is the duplicate").isEqualTo("DUPLICATE");
        assertThat(storedEvents("pk-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'DELIVERY' and entity_id = ? "
                + "and action = 'DELIVERY_PICKED_UP'", Integer.class, s.deliveryId())).isEqualTo(1);
    }

    @Test
    @DisplayName("an event delivered twice at once is applied once, and the loser is told it is a duplicate")
    void aConcurrentDuplicateIsIgnored() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return events.apply(deliveries.findById(s.deliveryId()).orElseThrow(), pickedUp("pk-2"));
            }));
        }
        go.countDown();
        var outcomes = new java.util.ArrayList<String>();
        for (var f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder("APPLIED", "DUPLICATE");
        assertThat(storedEvents("pk-2")).isEqualTo(1);
        assertThat(status()).isEqualTo("PICKED_UP");
    }
}
