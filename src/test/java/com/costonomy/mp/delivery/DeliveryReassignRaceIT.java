package com.costonomy.mp.delivery;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assignment timer and the sweep may only release a driver that has not been assigned (D-204 audit, gap 2): the
 * status, the deadline and the order are checked again under the row lock, so a driver who arrived between the caller's
 * look and the lock is kept, and two callers racing for the same expired delivery move it once.
 */
@AutoConfigureMockMvc
class DeliveryReassignRaceIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private DeliveryService deliveryService;

    private ApiClient api;
    private DeliveryFixture.Setup s;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        s = DeliveryFixture.readyForPickup(mvc, json, api, jdbc, paymentProvider, paymentJobs, "0.00", "0.00");
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    provider_delivery_id, quoted_amount, started_at, created_at)
                values (?, 'MOCK_EXPRESS', 1, 'BOOKING', 'BOOKED', 'old-1', 50, now(6), now(6))
                """, s.deliveryId());
        jdbc.update("update delivery set attempt_count = 1, provider_code = 'MOCK_EXPRESS', provider_delivery_id = 'old-1', "
                + "assignment_deadline = now(6) - interval 10 minute where id = ?", s.deliveryId());
    }

    private String status() {
        return jdbc.queryForObject("select status from delivery where id = ?", String.class, s.deliveryId());
    }

    private String courier() {
        return jdbc.queryForObject("select provider_delivery_id from delivery where id = ?", String.class, s.deliveryId());
    }

    private int attemptRows() {
        return jdbc.queryForObject("select count(*) from delivery_provider_attempt where delivery_id = ?", Integer.class,
                s.deliveryId());
    }

    @Test
    @DisplayName("a driver assigned between the timer's look and its lock is kept, not released")
    void anAssignedDriverIsNotReleased() {
        jdbc.update("update delivery set status = 'DRIVER_ASSIGNED', driver_name = 'Ravi' where id = ?", s.deliveryId());

        boolean moved = deliveryService.reassignIfAssignmentExpired(s.deliveryId(), "timer");

        assertThat(moved).isFalse();
        assertThat(status()).isEqualTo("DRIVER_ASSIGNED");
        assertThat(courier()).isEqualTo("old-1");
        assertThat(attemptRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select outcome from delivery_provider_attempt where delivery_id = ?", String.class,
                s.deliveryId())).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("an assignment still inside its deadline, or for an order that is no longer ready, is left alone")
    void notDueOrNotReadyIsLeftAlone() {
        jdbc.update("update delivery set assignment_deadline = now(6) + interval 5 minute where id = ?", s.deliveryId());
        assertThat(deliveryService.reassignIfAssignmentExpired(s.deliveryId(), "timer")).isFalse();
        assertThat(courier()).isEqualTo("old-1");

        jdbc.update("update delivery set assignment_deadline = now(6) - interval 10 minute where id = ?", s.deliveryId());
        jdbc.update("update supplier_order set status = 'CANCELLED', version = version + 1 where id = ?", s.orderId());
        assertThat(deliveryService.reassignIfAssignmentExpired(s.deliveryId(), "timer")).isFalse();
        assertThat(courier()).as("no new courier for an order that was cancelled").isEqualTo("old-1");
        assertThat(attemptRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("two callers racing for the same expired assignment move it once")
    void twoCallersMoveItOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return deliveryService.reassignIfAssignmentExpired(s.deliveryId(), "race");
                } catch (RuntimeException ex) {
                    return false;
                }
            }));
        }
        start.countDown();
        List<Boolean> outcomes = new ArrayList<>();
        for (var f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'DELIVERY' and entity_id = ? "
                + "and action = 'DELIVERY_REASSIGNED_AUTO'", Integer.class, s.deliveryId()))
                .as("exactly one of the two released the old courier").isEqualTo(1);
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select outcome from delivery_provider_attempt where delivery_id = ? "
                + "and attempt_number = 1", String.class, s.deliveryId()))
                .as("the old attempt was ended once").isNotEqualTo("BOOKED");
    }

    @Test
    @DisplayName("a manual reassign cannot send a second courier to goods a failed delivery already collected")
    void manualReassignRefusedAfterAFailedHandOver() {
        jdbc.update("update delivery set status = 'DELIVERY_FAILED', picked_up_at = now(6) - interval 1 hour where id = ?",
                s.deliveryId());

        assertThatThrownBy(() -> deliveryService.reassign(null, s.deliveryId(), "try again"))
                .isInstanceOf(BusinessException.class);

        assertThat(status()).isEqualTo("DELIVERY_FAILED");
        assertThat(courier()).isEqualTo("old-1");
        assertThat(attemptRows()).isEqualTo(1);
    }
}
