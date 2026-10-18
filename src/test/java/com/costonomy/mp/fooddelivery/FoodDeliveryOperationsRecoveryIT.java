package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.delivery.domain.DeliveryRecoveryTask;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryRecoveryTaskRepository;
import com.costonomy.mp.delivery.service.DeliveryRecoveryService;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryEventRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService.CreateRequest;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryEventService;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.wallet.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class FoodDeliveryOperationsRecoveryIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FoodDeliveryBookingService bookingService;
    @Autowired private FoodDeliveryEventService eventService;
    @Autowired private FoodDeliveryJobs jobs;
    @Autowired private FoodDeliveryRepository deliveries;
    @Autowired private FoodDeliveryEventRepository events;
    @Autowired private DeliveryRecoveryService recoveryService;
    @Autowired private DeliveryRecoveryTaskRepository recoveryTasks;
    @Autowired private WalletService wallet;
    @Autowired private com.costonomy.mp.delivery.service.ReliabilityProperties reliability;

    @Autowired @Qualifier("mockExpressDeliveryProvider") private MockDeliveryProvider express;
    @Autowired @Qualifier("mockSaverDeliveryProvider") private MockDeliveryProvider saver;

    private long restaurantId;
    private long outletId;
    private String adminToken;

    private String operator(String roleCode) throws Exception {
        var api = new ApiClient(mvc, json);
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        Long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = ?
                """, userId, roleCode);
        return token;
    }

    @BeforeEach
    void setUp() throws Exception {
        reliability.setEnabled(false);
        express.disarm();
        saver.disarm();
        adminToken = operator("OPS_ADMIN");
        var api = new ApiClient(mvc, json);
        String userToken = api.loginFresh();
        JsonNode created = api.post(userToken, "/api/v1/restaurants", Map.of(
                "name", "Paradise Food Recovery",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12", "city", "Hyderabad",
                        "state", "Telangana", "pincode", "500034", "contactName", "Asha", "contactPhone", "+919876511111",
                        "latitude", "17.4156", "longitude", "78.4347"))).get("data");
        restaurantId = created.get("id").asLong();
        outletId = created.at("/outlets/0/id").asLong();
        wallet.topUp(outletId, new BigDecimal("2000"), "test");
    }

    @AfterEach
    void restore() {
        reliability.setEnabled(true);
        express.disarm();
        saver.disarm();
    }

    private CreateRequest request(String key, BigDecimal maxFee) {
        return new CreateRequest(restaurantId, outletId, key, "POS-RECOVER", "Asha", "+919876543210",
                "Flat 4B, Jubilee Hills", "Near the park", new BigDecimal("17.4326"), new BigDecimal("78.4071"),
                "Hyderabad", "Telangana", "500033", "Leave at door",
                new BigDecimal("640"), new BigDecimal("2.5"), 45, maxFee);
    }

    private CreateRequest request() {
        return request(UUID.randomUUID().toString(), null);
    }

    private FoodDelivery reload(long id) {
        return deliveries.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("booking timeout where provider actually accepted is recovered via reference lookup")
    void providerBookingTimeoutWithAcceptedLookupRecovery() {
        saver.arm(MockDeliveryProvider.Failure.BOOKING_TIMEOUT_BUT_ACCEPTED);

        var d = bookingService.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));

        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(d.getProviderCode()).isEqualTo("MOCK_SAVER");
        assertThat(d.getProviderDeliveryId()).isNotBlank();
        assertThat(d.isReconciliationRequired()).isFalse();

        var attempts = jdbc.queryForList("select * from food_delivery_attempt where food_delivery_id = ? order by attempt_number", d.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0)).containsEntry("outcome", "BOOKED");
        assertThat(attempts.get(0)).containsEntry("provider_code", "MOCK_SAVER");
    }

    @Test
    @DisplayName("ambiguous booking timeout without remote confirmation flags delivery for operator reconciliation")
    void ambiguousBookingTimeoutFlagsReconciliation() {
        saver.arm(MockDeliveryProvider.Failure.BOOKING_TIMEOUT_LOOKUP_UNSUPPORTED);

        var d = bookingService.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));

        var reloaded = reload(d.getId());
        assertThat(reloaded.isReconciliationRequired()).isTrue();
        assertThat(reloaded.getReconciliationReason()).contains("ambiguous with MOCK_SAVER");

        var attempts = jdbc.queryForList("select * from food_delivery_attempt where food_delivery_id = ? order by attempt_number", d.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0)).containsEntry("outcome", "FAILED")
                .containsEntry("failure_code", "AMBIGUOUS_PROVIDER_OUTCOME");
    }

    @Test
    @DisplayName("stale PENDING attempts left after process crash are swept and abandoned")
    void stalePendingAttemptSweepAfterCrash() {
        var d = bookingService.create(request());
        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);

        // Simulate an attempt stranded in PENDING 5 minutes ago
        jdbc.update("insert into food_delivery_attempt (food_delivery_id, attempt_number, attempt_type, provider_code, outcome, rank_position, quoted_amount, started_at) " +
                "values (?, 99, 'INITIAL', 'MOCK_SAVER', 'PENDING', 1, 50.00, utc_timestamp(6) - interval 5 minute)", d.getId());

        int swept = jobs.runStalePendingSweep(Instant.now());
        assertThat(swept).isGreaterThanOrEqualTo(1);

        var attempt = jdbc.queryForMap("select outcome, ended_cause from food_delivery_attempt where food_delivery_id = ? and attempt_number = 99", d.getId());
        assertThat(attempt.get("outcome")).isEqualTo("FAILED");
        assertThat(attempt.get("ended_cause")).isEqualTo("ABANDONED_STALE_PENDING");
    }

    @Test
    @DisplayName("provider cancellation failure persists durable recovery task and completes on retry")
    void providerCancellationFailureSchedulesRecoveryTask() {
        var d = bookingService.create(request());
        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        String provId = d.getProviderDeliveryId();

        saver.arm(MockDeliveryProvider.Failure.CANCEL_FAILS);

        var cancelled = bookingService.cancel(d.getId(), "Customer changed mind");
        assertThat(cancelled.getStatus()).isEqualTo(FoodDeliveryStatus.CANCELLED);

        // Provider cancellation failed, durable task should exist
        List<DeliveryRecoveryTask> tasks = recoveryTasks.findByFlowAndDeliveryIdOrderByCreatedAtAsc("FOOD", d.getId());
        assertThat(tasks).isNotEmpty();
        var cancelTask = tasks.stream().filter(t -> t.getTaskType().equals("PROVIDER_CANCEL")).findFirst().orElseThrow();
        assertThat(cancelTask.getStatus()).isEqualTo("PENDING");
        assertThat(cancelTask.getProviderDeliveryId()).isEqualTo(provId);

        // Disarm and execute scheduled recovery
        saver.disarm();
        int recovered = recoveryService.processDueTasks();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        var completedTask = recoveryTasks.findById(cancelTask.getId()).orElseThrow();
        assertThat(completedTask.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("duplicate and out-of-order events are ingested idempotently without status rewinds")
    void duplicateAndOutOfOrderEvents() {
        var d = bookingService.create(request());
        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);

        Instant now = Instant.now();
        // 1. Driver assigned event
        var evtAssigned = new DeliveryProvider.ProviderEvent("EVT-ASSIGNED-1", DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED,
                "Driver assigned", now);
        String disp0 = eventService.apply(d, evtAssigned, "MOCK_SAVER", null);
        assertThat(disp0).isEqualTo("APPLIED");
        assertThat(reload(d.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.DRIVER_ASSIGNED);

        // 2. Picked up event
        var evt1 = new DeliveryProvider.ProviderEvent("EVT-PICKUP-1", DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                "Rider picked up order", now.plusSeconds(10));
        String disp1 = eventService.apply(d, evt1, "MOCK_SAVER", null);
        assertThat(disp1).isEqualTo("APPLIED");
        assertThat(reload(d.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.PICKED_UP);

        // 3. Duplicate picked up event with same providerEventId
        var evt2 = new DeliveryProvider.ProviderEvent("EVT-PICKUP-1", DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                "Rider picked up order (duplicate)", now.plusSeconds(20));
        String disp2 = eventService.apply(d, evt2, "MOCK_SAVER", null);
        assertThat(disp2).isEqualTo("DUPLICATE");
        assertThat(reload(d.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.PICKED_UP);

        // Check disposition in timeline
        var allEvents = events.findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(d.getId());
        assertThat(allEvents).anyMatch(e -> "EVT-PICKUP-1".equals(e.getProviderEventId()) && "APPLIED".equals(e.getDisposition()));

        // 4. Out-of-order earlier event (DRIVER_ASSIGNED after PICKED_UP)
        var evt3 = new DeliveryProvider.ProviderEvent("EVT-DRIVER-LATE", DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED,
                "Late driver assigned", now.minusSeconds(10));
        String disp3 = eventService.apply(d, evt3, "MOCK_SAVER", null);
        assertThat(disp3).isEqualTo("OUT_OF_ORDER");
        // Status did NOT rewind to DRIVER_ASSIGNED
        assertThat(reload(d.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.PICKED_UP);

        allEvents = events.findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(d.getId());
        assertThat(allEvents).anyMatch(e -> "EVT-DRIVER-LATE".equals(e.getProviderEventId()) && "OUT_OF_ORDER".equals(e.getDisposition()));
    }

    @Test
    @DisplayName("public tracking endpoint exposes sanitized data with zero customer phone or internal quote leakage")
    void publicTrackingSanitization() throws Exception {
        var d = bookingService.create(request());

        mvc.perform(get("/api/v1/public/food-deliveries/" + d.getTrackingToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackingToken").value(d.getTrackingToken()))
                .andExpect(jsonPath("$.data.status").value("PROVIDER_SELECTED"))
                .andExpect(jsonPath("$.data.customerPhone").doesNotExist())
                .andExpect(jsonPath("$.data.driverPhone").doesNotExist())
                .andExpect(jsonPath("$.data.providerCode").doesNotExist())
                .andExpect(jsonPath("$.data.quotedAmount").doesNotExist())
                .andExpect(jsonPath("$.data.maxFee").doesNotExist())
                .andExpect(jsonPath("$.data.pickupAddress").exists())
                .andExpect(jsonPath("$.data.dropAddress").exists());
    }

    @Test
    @DisplayName("admin inspection and operational alerts endpoints provide full diagnostic timeline")
    void adminInspectionAndAlerts() throws Exception {
        var d = bookingService.create(request());

        mvc.perform(get("/api/v1/admin/food-deliveries/" + d.getId() + "/inspect")
                        .header("Authorization", "Bearer " + adminToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery.id").value(d.getId()))
                .andExpect(jsonPath("$.data.quotes").isArray())
                .andExpect(jsonPath("$.data.attempts").isArray())
                .andExpect(jsonPath("$.data.timeline").isArray())
                .andExpect(jsonPath("$.data.nextAction").exists());

        mvc.perform(get("/api/v1/admin/deliveries/operational-alerts")
                        .header("Authorization", "Bearer " + adminToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stuckDeliveries").isArray())
                .andExpect(jsonPath("$.data.reconciliationRequiredCount").isNumber())
                .andExpect(jsonPath("$.data.pendingRecoveryTasksCount").isNumber())
                .andExpect(jsonPath("$.data.exhaustedRecoveryTasksCount").isNumber())
                .andExpect(jsonPath("$.data.failedRefundTasksCount").isNumber())
                .andExpect(jsonPath("$.data.failedCancellationTasksCount").isNumber());
    }
}
