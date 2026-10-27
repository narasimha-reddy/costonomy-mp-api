package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.support.LocalCommon;

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
import com.costonomy.mp.fooddelivery.service.FoodDeliveryReconciliationJob;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryUnclaimedEventHandler;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class FoodDeliveryOperationsRecoveryIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FoodDeliveryBookingService bookingService;
    @Autowired private FoodDeliveryEventService eventService;
    @Autowired private FoodDeliveryUnclaimedEventHandler unclaimedEventHandler;
    @Autowired private FoodDeliveryJobs jobs;
        @Autowired private FoodDeliveryReconciliationJob reconciliationJob;
    @Autowired private FoodDeliveryRepository deliveries;
    @Autowired private FoodDeliveryEventRepository events;
    @Autowired private DeliveryRecoveryService recoveryService;
    @Autowired private DeliveryRecoveryTaskRepository recoveryTasks;
    @Autowired private WalletService wallet;
    @Autowired private com.costonomy.mp.delivery.service.CourierPayableSyncService payableSyncService;
    @Autowired private com.costonomy.mp.delivery.service.CourierPayableProperties payableProps;
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
        Long userId = LocalCommon.userId("+91" + phone);
        LocalCommon.grantPlatform(userId, roleCode);
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
        jdbc.update("update food_delivery set last_polled_at = utc_timestamp(6)");
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

    @Test
    @DisplayName("recovery scheduling is idempotent for long provider delivery references")
    void cancellationTaskIsIdempotentForLongProviderReference() {
        String providerDeliveryId = "provider-" + "x".repeat(190);

        var first = recoveryService.scheduleCancellation("SUPPLIER", 7001L, "MOCK_EXPRESS",
                providerDeliveryId, "Test cancellation");
        var duplicate = recoveryService.scheduleCancellation("SUPPLIER", 7001L, "MOCK_EXPRESS",
                providerDeliveryId, "Test cancellation retry");

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(duplicate.getIdempotencyRef()).hasSizeLessThanOrEqualTo(255);
        Integer taskCount = jdbc.queryForObject(
                "select count(*) from delivery_recovery_task where idempotency_ref = ?",
                Integer.class, first.getIdempotencyRef());
        assertThat(taskCount).isEqualTo(1);
    }

    @Test
    @DisplayName("two database workers cannot claim one due recovery task")
    void concurrentWorkersClaimRecoveryTaskOnlyOnce() throws Exception {
        var task = recoveryService.scheduleCancellation("SUPPLIER", 7002L, "MOCK_EXPRESS",
                "claim-race", "Test cancellation");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> claim = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Claim race did not start");
                }
                Instant now = Instant.now();
                return recoveryTasks.claimTask(task.getId(), now, now.minusSeconds(120), UUID.randomUUID().toString());
            };
            var first = workers.submit(claim);
            var second = workers.submit(claim);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
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
        @DisplayName("reclaimed refund task does not add an existing wallet credit twice")
        void staleRefundTaskReusesLedgerCreditAndRebuildsProjection() {
        var d = bookingService.create(request());
        BigDecimal refundAmount = new BigDecimal("12.5000");
        var task = recoveryService.scheduleRefund(d.getId(), outletId, "adjust", refundAmount,
            "Unused delivery hold");

        assertThat(recoveryService.executeTask(task.getId())).isTrue();
        String reference = task.getIdempotencyRef();
        assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where reference = ?",
            Integer.class, reference)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select fee_refunded_amount from food_delivery where id = ?",
            BigDecimal.class, d.getId())).isEqualByComparingTo(refundAmount);

        jdbc.update("""
            update delivery_recovery_task
               set status = 'PROCESSING', claimed_at = utc_timestamp(6) - interval 3 minute,
                   claim_token = 'expired-claim'
             where id = ?
            """, task.getId());

        assertThat(recoveryService.executeTask(task.getId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where reference = ?",
            Integer.class, reference)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select fee_refunded_amount from food_delivery where id = ?",
            BigDecimal.class, d.getId())).isEqualByComparingTo(refundAmount);
        }

        @Test
        @DisplayName("repeated food status polls deduplicate their synthetic provider event")
        void repeatedStatusPollIsIdempotent() {
                var d = bookingService.create(request());
                assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);

                MockDeliveryProvider provider = "MOCK_EXPRESS".equals(d.getProviderCode()) ? express : saver;
                provider.simulate(d.getProviderDeliveryId(), DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED,
                                "Rider assigned");

                assertThat(reconciliationJob.runReconciliation(Instant.now())).isEqualTo(1);
                jdbc.update("update food_delivery set last_polled_at = utc_timestamp(6) - interval 3 minute where id = ?", d.getId());
                assertThat(reconciliationJob.runReconciliation(Instant.now())).isEqualTo(1);

                Integer pollEvents = jdbc.queryForObject("""
                                select count(*) from food_delivery_event
                                 where food_delivery_id = ? and provider_code = ?
                                   and provider_event_id like 'poll-%' and provider_status = 'DRIVER_ASSIGNED'
                                """, Integer.class, d.getId(), d.getProviderCode());
                assertThat(pollEvents).isEqualTo(1);
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
                .andExpect(jsonPath("$.data.dropAddress").doesNotExist());
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

    @Test
    @DisplayName("pickup event settles unused fee to wallet without nested lock-wait timeouts")
    void pickupEventTriggersFeeSettlementWithoutLockWait() {
        BigDecimal maxFee = new BigDecimal("200.00");
        var d = bookingService.create(request(UUID.randomUUID().toString(), maxFee));
        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(d.getFee()).isNotNull();
        BigDecimal expectedRefund = maxFee.subtract(d.getFee());

        // Courier arrived and picked up
        var pickupEvent = new DeliveryProvider.ProviderEvent(
                "EVT-PICKUP-" + d.getId(),
                DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                "Food picked up by courier",
                Instant.now()
        );

        String disp = eventService.apply(d, d.getProviderCode(), d.getProviderDeliveryId(), pickupEvent, null);
        assertThat(disp).isEqualTo("APPLIED");

        var reloaded = reload(d.getId());
        assertThat(reloaded.getStatus()).isEqualTo(FoodDeliveryStatus.PICKED_UP);
        assertThat(reloaded.getPickedUpAt()).isNotNull();
        assertThat(reloaded.getFeeRefundedAmount()).isEqualByComparingTo(expectedRefund);
        assertThat(reloaded.getFeeRefundRef()).isEqualTo("food-delivery-" + d.getId() + "-adjust");
    }

    @Test
    @DisplayName("driver cancellation rebooks next courier without nested lock-wait timeouts")
    void driverCancellationRebooksNextCourierWithoutLockWait() {
        var d = bookingService.create(request(UUID.randomUUID().toString(), new BigDecimal("200.00")));
        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        String initialProvider = d.getProviderCode();
        String initialDeliveryId = d.getProviderDeliveryId();
        int initialAttempts = d.getAttemptCount();

        // Initial driver cancels before pickup
        var cancelEvent = new DeliveryProvider.ProviderEvent(
                "EVT-RIDER-CANCEL-" + d.getId(),
                DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED,
                "Rider vehicle issue",
                Instant.now()
        );

        String disp = eventService.apply(d, initialProvider, initialDeliveryId, cancelEvent, null);
        assertThat(disp).isEqualTo("APPLIED");

        var reloaded = reload(d.getId());
        assertThat(reloaded.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(reloaded.getAttemptCount()).isGreaterThan(initialAttempts);
        assertThat(reloaded.getProviderDeliveryId()).isNotNull();
        assertThat(reloaded.getProviderDeliveryId()).isNotEqualTo(initialDeliveryId);
    }

    @Test
    @DisplayName("reject stale events from replaced provider consignments")
    void rejectStaleEventsFromReplacedProviderConsignment() {
        var d = bookingService.create(request(UUID.randomUUID().toString(), new BigDecimal("200.00")));
        String oldProviderCode = d.getProviderCode();
        String oldProviderDeliveryId = d.getProviderDeliveryId();

        // Stand down and rebook to a new consignment
        bookingService.rebook(d.getId(), oldProviderDeliveryId, "TEST_STAND_DOWN", "Replacing driver");
        var active = reload(d.getId());
        String activeDeliveryId = active.getProviderDeliveryId();
        assertThat(activeDeliveryId).isNotEqualTo(oldProviderDeliveryId);

        // 1. Late driver details from old consignment
        eventService.recordDriver(active, oldProviderCode, oldProviderDeliveryId, "Old Stale Driver", "+919999900000", "Bike");
        var afterDriver = reload(d.getId());
        assertThat(afterDriver.getDriverName()).isNotEqualTo("Old Stale Driver");

        // 2. Late DRIVER_CANCELLED from old consignment
        var lateCancelEvent = new DeliveryProvider.ProviderEvent(
                "EVT-OLD-CANCEL",
                DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED,
                "Late cancel from old courier",
                Instant.now()
        );
        String cancelDisp = eventService.apply(active, oldProviderCode, oldProviderDeliveryId, lateCancelEvent, null);
        assertThat(cancelDisp).isEqualTo("IGNORED");
        var afterCancel = reload(d.getId());
        // Status must not have changed and active consignment must remain intact
        assertThat(afterCancel.getProviderDeliveryId()).isEqualTo(activeDeliveryId);

        // 3. Late PICKED_UP from old consignment with payload containing food delivery reference
        var latePickupPayload = json.createObjectNode()
                .put("reference", "FD-" + d.getId())
                .put("provider_delivery_id", oldProviderDeliveryId)
                .put("status", "PICKED_UP");
        var latePickupEvent = new DeliveryProvider.ProviderEvent(
                "EVT-OLD-PICKUP",
                DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                "Late pickup from old courier",
                Instant.now()
        );
        String pickupDisp = eventService.apply(active, oldProviderCode, oldProviderDeliveryId, latePickupEvent, latePickupPayload);
        assertThat(pickupDisp).isEqualTo("IGNORED");
        var afterPickup = reload(d.getId());
        assertThat(afterPickup.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(afterPickup.getPickedUpAt()).isNull();
    }

    @Test
    @DisplayName("webhook deduplication is stable with and without event_id")
    void stableWebhookDeduplicationWithAndWithoutEventId() {
        var d = bookingService.create(request());

        // 1. Webhook with provider event ID
        var payloadWithId = json.createObjectNode()
                .put("event_id", "WH-EVT-1001")
                .put("provider_delivery_id", d.getProviderDeliveryId())
                .put("status", "DRIVER_ASSIGNED")
                .put("rider_name", "Rajesh");

        boolean claimedFirst = unclaimedEventHandler.handle(d.getProviderCode(), d.getProviderDeliveryId(), payloadWithId);
        assertThat(claimedFirst).isTrue();
        assertThat(reload(d.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.DRIVER_ASSIGNED);

        boolean claimedRetry = unclaimedEventHandler.handle(d.getProviderCode(), d.getProviderDeliveryId(), payloadWithId);
        assertThat(claimedRetry).isTrue();
        // Duplicate event is ignored, only 1 APPLIED event exists
        long appliedCount = events.findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(d.getId()).stream()
                .filter(e -> "WH-EVT-1001".equals(e.getProviderEventId()) && "APPLIED".equals(e.getDisposition()))
                .count();
        assertThat(appliedCount).isEqualTo(1);

        // 2. Webhook WITHOUT provider event ID: deterministic payload hash fallback
        var payloadWithoutId = json.createObjectNode()
                .put("delivery_id", d.getProviderDeliveryId())
                .put("status", "PICKED_UP")
                .put("description", "Food collected from kitchen");

        boolean noIdFirst = unclaimedEventHandler.handle(d.getProviderCode(), d.getProviderDeliveryId(), payloadWithoutId);
        assertThat(noIdFirst).isTrue();
        assertThat(reload(d.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.PICKED_UP);

        // Duplicate retry with identical payload without event ID
        boolean noIdRetry = unclaimedEventHandler.handle(d.getProviderCode(), d.getProviderDeliveryId(), payloadWithoutId);
        assertThat(noIdRetry).isTrue();

        long pickupAppliedCount = events.findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(d.getId()).stream()
                .filter(e -> e.getProviderEventId() != null && e.getProviderEventId().startsWith("evt_") && "APPLIED".equals(e.getDisposition()))
                .count();
        assertThat(pickupAppliedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("public tracking expires 24 hours after terminal state")
    void publicTrackingExpiry() throws Exception {
        var d = bookingService.create(request());

        // Transition to terminal state DELIVERED
        var deliveredEvent = new DeliveryProvider.ProviderEvent(
                "EVT-DELIVERED-" + d.getId(),
                DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                "Delivered to customer",
                Instant.now()
        );
        eventService.apply(d, d.getProviderCode(), d.getProviderDeliveryId(), deliveredEvent, null);

        var delivered = reload(d.getId());
        assertThat(delivered.getStatus()).isEqualTo(FoodDeliveryStatus.DELIVERED);
        assertThat(delivered.getTrackingExpiresAt()).isNotNull();
        assertThat(delivered.getTrackingExpiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600));

        // Active token returns 200
        mvc.perform(get("/api/v1/public/food-deliveries/" + d.getTrackingToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Simulate expiration past 24 hours
        jdbc.update("update food_delivery set tracking_expires_at = utc_timestamp(6) - interval 1 hour where id = ?", d.getId());

        // Expired token returns 404
        mvc.perform(get("/api/v1/public/food-deliveries/" + d.getTrackingToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    private String outletUser(String roleCode, Long outletId) throws Exception {
        var api = new ApiClient(mvc, json);
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        Long userId = LocalCommon.userId("+91" + phone);
        LocalCommon.grant(userId, roleCode, "OUTLET", outletId);
        return token;
    }

    @Test
    @DisplayName("enforces OUTLET_EDIT permission for create and cancel, OUTLET_VIEW for read")
    void outletPermissionsEnforced() throws Exception {
        // User with REST_RECEIVING_STAFF has OUTLET_VIEW only (no OUTLET_EDIT)
        String viewOnlyToken = outletUser("REST_RECEIVING_STAFF", outletId);
        // User with REST_STORE_MANAGER has OUTLET_EDIT
        String managerToken = outletUser("REST_STORE_MANAGER", outletId);

        var createPayload = Map.ofEntries(
                Map.entry("externalOrderRef", "POS-AUTH-TEST"),
                Map.entry("customerName", "Asha"),
                Map.entry("customerPhone", "+919876543210"),
                Map.entry("dropAddress", "Flat 4B, Jubilee Hills"),
                Map.entry("dropCity", "Hyderabad"),
                Map.entry("dropState", "Telangana"),
                Map.entry("dropPincode", "500033"),
                Map.entry("dropLatitude", 17.4326),
                Map.entry("dropLongitude", 78.4071),
                Map.entry("orderValue", 500),
                Map.entry("weightKg", 1.5),
                Map.entry("maxFee", 150)
        );

        // 1. Create with OUTLET_VIEW only -> 403 Forbidden
        mvc.perform(post("/api/v1/outlets/" + outletId + "/food-deliveries")
                        .header("Authorization", "Bearer " + viewOnlyToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createPayload)))
                .andExpect(status().isForbidden());

        // 2. Create with OUTLET_EDIT -> 200 OK
        String responseJson = mvc.perform(post("/api/v1/outlets/" + outletId + "/food-deliveries")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createPayload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long createdId = json.readTree(responseJson).at("/data/id").asLong();
        assertThat(createdId).isNotNull();

        // 3. Read with OUTLET_VIEW -> 200 OK
        mvc.perform(get("/api/v1/outlets/" + outletId + "/food-deliveries/" + createdId)
                        .header("Authorization", "Bearer " + viewOnlyToken))
                .andExpect(status().isOk());

        // 4. Cancel with OUTLET_VIEW only -> 403 Forbidden
        mvc.perform(post("/api/v1/outlets/" + outletId + "/food-deliveries/" + createdId + "/cancel")
                        .header("Authorization", "Bearer " + viewOnlyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "Cancelled"))))
                .andExpect(status().isForbidden());

        // 5. Cancel with OUTLET_EDIT -> 200 OK
        mvc.perform(post("/api/v1/outlets/" + outletId + "/food-deliveries/" + createdId + "/cancel")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "Cancelled"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    private DeliveryProvider.ProviderEvent providerEvent(String id, DeliveryProvider.ProviderDeliveryStatus status, String why) {
        return new DeliveryProvider.ProviderEvent(id, status, why, Instant.now());
    }

    /** A booked delivery the courier already has: picked up, with the courier's fee fixed. */
    private FoodDelivery pickedUp(String fee) {
        var d = bookingService.create(request());
        d.setStatus(FoodDeliveryStatus.PICKED_UP);
        d.setPickedUpAt(Instant.now());
        d.setFee(new BigDecimal(fee));
        return deliveries.save(d);
    }

    private BigDecimal refundedToWalletOrGateway(long id) {
        return reload(id).getFeeRefundedAmount();
    }

    private List<Map<String, Object>> foodPayables(long id) {
        return jdbc.queryForList("select state, due_reason, amount_due from courier_payable "
                + "where delivery_id = ? and source_type = 'FOOD_DELIVERY' order by id", id);
    }

    private int gatewayRefundCount(long id) {
        return jdbc.queryForObject("select count(*) from gateway_refund where source_type = 'FOOD_DELIVERY' and source_id = ?",
                Integer.class, id);
    }

    @Test
    @DisplayName("Provider CANCELLED before pickup: the consignment is stood down and the next courier is booked, with nothing refunded or flagged")
    void providerCancellationBeforePickup_triggersRebooking() {
        var d = bookingService.create(request());
        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        String oldPid = d.getProviderDeliveryId();
        BigDecimal refundedBefore = refundedToWalletOrGateway(d.getId());

        String disposition = eventService.apply(d,
                providerEvent("evt-cancel-pre-1", DeliveryProvider.ProviderDeliveryStatus.CANCELLED, "Rider unassigned by partner"),
                d.getProviderCode(), null);
        assertThat(disposition).isEqualTo("APPLIED");

        var oldAttempt = jdbc.queryForMap(
                "select outcome, ended_cause from food_delivery_attempt where food_delivery_id = ? and provider_delivery_id = ?",
                d.getId(), oldPid);
        assertThat(oldAttempt.get("outcome")).isEqualTo("CANCELLED");
        assertThat(oldAttempt.get("ended_cause")).isEqualTo("PROVIDER_CANCELLED");
        var reloaded = reload(d.getId());
        assertThat(reloaded.getStatus()).isIn(FoodDeliveryStatus.PROVIDER_SELECTED, FoodDeliveryStatus.PROVIDER_UNAVAILABLE);
        assertThat(reloaded.getProviderDeliveryId()).as("a new courier, or none yet, never the cancelled one")
                .isNotEqualTo(oldPid);
        assertThat(reloaded.isReconciliationRequired()).isFalse();
        assertThat(reloaded.getFeeRefundedAmount()).as("the hold stays while another courier is found")
                .isEqualByComparingTo(refundedBefore);
        assertThat(foodPayables(d.getId())).as("a courier that never had the food is owed nothing")
                .noneMatch(row -> "DUE".equals(row.get("state")) || "PAID".equals(row.get("state")));
    }

    @Test
    @DisplayName("Provider CANCELLED after pickup: FAILED, flagged for operations, nothing refunded, courier payable DUE (D-204)")
    void providerCancellationAfterPickup_isFlaggedAndTheCourierStaysOwed() {
        var d = pickedUp("80.00");
        BigDecimal balance = wallet.balanceOf(outletId);
        BigDecimal refunded = refundedToWalletOrGateway(d.getId());

        String disposition = eventService.apply(d,
                providerEvent("evt-cancel-post-1", DeliveryProvider.ProviderDeliveryStatus.CANCELLED, "Vehicle broke down after pickup"),
                d.getProviderCode(), null);
        assertThat(disposition).isEqualTo("APPLIED");

        var reloaded = reload(d.getId());
        assertThat(reloaded.getStatus()).isEqualTo(FoodDeliveryStatus.FAILED);
        assertThat(reloaded.getFailureCode()).isEqualTo("PROVIDER_CANCELLED_POST_PICKUP");
        assertThat(reloaded.isReconciliationRequired()).as("operations can see it").isTrue();
        assertThat(reloaded.getReconciliationReason()).contains("after pickup");
        assertThat(reloaded.getFeeRefundedAmount()).as("no refund after pickup").isEqualByComparingTo(refunded);
        assertThat(wallet.balanceOf(outletId)).isEqualByComparingTo(balance);
        assertThat(gatewayRefundCount(d.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from delivery_recovery_task where task_type = 'WALLET_REFUND' "
                + "and delivery_id = ?", Integer.class, d.getId())).isZero();

        var payables = foodPayables(d.getId());
        assertThat(payables).hasSize(1);
        assertThat(payables.get(0)).containsEntry("state", "DUE").containsEntry("due_reason", "POST_PICKUP_FAILURE");
        assertThat((BigDecimal) payables.get(0).get("amount_due")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Provider DELIVERY_FAILED before pickup is a failed pickup: stand the courier down and rebook, no flag, no courier owed")
    void deliveryFailedBeforePickup_isTreatedAsAFailedPickup() {
        var d = bookingService.create(request());
        String oldPid = d.getProviderDeliveryId();

        String disposition = eventService.apply(d,
                providerEvent("evt-df-pre-1", DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED, "Could not collect"),
                d.getProviderCode(), null);
        assertThat(disposition).isEqualTo("APPLIED");

        var reloaded = reload(d.getId());
        assertThat(reloaded.getStatus()).isNotEqualTo(FoodDeliveryStatus.FAILED);
        assertThat(reloaded.getProviderDeliveryId()).isNotEqualTo(oldPid);
        assertThat(reloaded.isReconciliationRequired()).isFalse();
        assertThat(jdbc.queryForObject("select ended_cause from food_delivery_attempt where food_delivery_id = ? "
                + "and provider_delivery_id = ?", String.class, d.getId(), oldPid)).isEqualTo("PICKUP_FAILED");
        assertThat(foodPayables(d.getId())).noneMatch(row -> "DUE".equals(row.get("state")));
    }

    @Test
    @DisplayName("Provider DELIVERY_FAILED after pickup: FAILED with the courier still owed, flagged, nothing refunded")
    void deliveryFailedAfterPickup_isFlaggedAndTheCourierStaysOwed() {
        var d = pickedUp("65.00");
        BigDecimal refunded = refundedToWalletOrGateway(d.getId());

        String disposition = eventService.apply(d,
                providerEvent("evt-df-post-1", DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED, "Customer unreachable"),
                d.getProviderCode(), null);
        assertThat(disposition).isEqualTo("APPLIED");

        var reloaded = reload(d.getId());
        assertThat(reloaded.getStatus()).isEqualTo(FoodDeliveryStatus.FAILED);
        assertThat(reloaded.getFailureCode()).isEqualTo("DELIVERY_FAILED");
        assertThat(reloaded.isReconciliationRequired()).isTrue();
        assertThat(reloaded.getFeeRefundedAmount()).isEqualByComparingTo(refunded);
        assertThat(gatewayRefundCount(d.getId())).isZero();
        var payables = foodPayables(d.getId());
        assertThat(payables).hasSize(1);
        assertThat(payables.get(0)).containsEntry("state", "DUE").containsEntry("due_reason", "POST_PICKUP_FAILURE");
    }

    // ── The operator's way out (D-204 audit, gaps 7 and 14) ──────────────

    private org.springframework.test.web.servlet.ResultActions resolve(String token, long id, String outcome, String reason)
            throws Exception {
        return mvc.perform(post("/api/v1/admin/food-deliveries/" + id + "/resolve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("outcome", outcome, "reason", reason))));
    }

    /** Picked up, settled down to the courier's fee, then the courier failed at hand-over: flagged, courier owed. */
    private FoodDelivery failedAtHandOver() {
        var d = pickedUp("80.00");
        bookingService.settleFee(d.getId());
        String disposition = eventService.apply(reload(d.getId()),
                providerEvent("evt-ho-" + UUID.randomUUID(), DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED, "Customer unreachable"),
                d.getProviderCode(), null);
        assertThat(disposition).isEqualTo("APPLIED");
        var failed = reload(d.getId());
        assertThat(failed.getStatus()).isEqualTo(FoodDeliveryStatus.FAILED);
        assertThat(failed.isReconciliationRequired()).isTrue();
        return failed;
    }

    @Test
    @DisplayName("a food delivery the courier later confirms delivered clears the stale failure and flag, keeps the history, and inspects as over")
    void foodFailureThenDelivered() throws Exception {
        var failed = failedAtHandOver();
        var inspected = json.readTree(mvc.perform(get("/api/v1/admin/food-deliveries/" + failed.getId() + "/inspect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data");
        assertThat(inspected.get("nextAction").asText()).isEqualTo("OPERATOR_RECONCILIATION");
        assertThat(inspected.at("/delivery/reconciliationRequired").asBoolean()).isTrue();

        String disposition = eventService.apply(reload(failed.getId()),
                providerEvent("evt-late-" + UUID.randomUUID(), DeliveryProvider.ProviderDeliveryStatus.DELIVERED, "Handed over after all"),
                failed.getProviderCode(), null);
        assertThat(disposition).isEqualTo("APPLIED");

        var after = reload(failed.getId());
        assertThat(after.getStatus()).isEqualTo(FoodDeliveryStatus.DELIVERED);
        assertThat(after.getFailureCode()).isNull();
        assertThat(after.getFailureReason()).isNull();
        assertThat(after.isReconciliationRequired()).isFalse();
        assertThat(after.getReconciliationReason()).isNull();
        assertThat(after.getDeliveredAt()).isNotNull();

        var over = json.readTree(mvc.perform(get("/api/v1/admin/food-deliveries/" + failed.getId() + "/inspect")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data");
        assertThat(over.get("nextAction").asText()).isEqualTo("TERMINAL");
        assertThat(over.at("/delivery/reconciliationRequired").asBoolean()).isFalse();
        assertThat(over.at("/delivery/failureCode").isNull()).isTrue();

        // The history stays: the failure event, the delivered event, and an audit row naming what was superseded.
        assertThat(jdbc.queryForList("select status from food_delivery_event where food_delivery_id = ? "
                + "and provider_event_id like 'evt-%' order by id", String.class, failed.getId()))
                .endsWith("FAILED", "DELIVERED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'FOOD_DELIVERY' and entity_id = ? "
                + "and action = 'FOOD_DELIVERY_FAILURE_SUPERSEDED'", Integer.class, failed.getId())).isEqualTo(1);
        assertThat(foodPayables(failed.getId()).get(0)).containsEntry("state", "DUE").containsEntry("due_reason", "DELIVERED");

        // An operator's resolve afterwards finds nothing to do and refunds nothing.
        resolve(adminToken, failed.getId(), "CLEAR_FLAG", "Already delivered").andExpect(status().isOk());
        assertThat(reload(failed.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("a food delivery flagged after a failed hand-over is listed for operations, with what to do")
    void aFlaggedFoodDeliveryIsListedInTheExceptions() throws Exception {
        var failed = failedAtHandOver();

        var res = mvc.perform(get("/api/v1/admin/deliveries/exceptions").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        boolean listed = false;
        for (JsonNode e : json.readTree(res).at("/data")) {
            if ("FOOD_RECONCILIATION_REQUIRED".equals(e.get("kind").asText()) && e.get("deliveryId").asLong() == failed.getId()) {
                listed = true;
                assertThat(e.get("action").asText()).contains("/admin/food-deliveries/{id}/resolve");
            }
        }
        assertThat(listed).isTrue();
    }

    @Test
    @DisplayName("CLEAR_FLAG clears the flag with a note, changes nothing else, refunds nothing, and a repeat is harmless")
    void clearFlagOnly() throws Exception {
        var failed = failedAtHandOver();
        BigDecimal refunded = reload(failed.getId()).getFeeRefundedAmount();

        resolve(adminToken, failed.getId(), "CLEAR_FLAG", "Spoke to the restaurant; nothing to do")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reconciliationRequired").value(false))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        resolve(adminToken, failed.getId(), "CLEAR_FLAG", "Again").andExpect(status().isOk());

        var after = reload(failed.getId());
        assertThat(after.isReconciliationRequired()).isFalse();
        assertThat(after.getFeeRefundedAmount()).isEqualByComparingTo(refunded);
        assertThat(foodPayables(failed.getId()).get(0)).containsEntry("state", "DUE");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'FOOD_DELIVERY' and entity_id = ? "
                + "and action = 'FOOD_DELIVERY_CLEAR_FLAG_BY_OPERATIONS'", Integer.class, failed.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("CANCEL_AND_REFUND after a failed hand-over closes it as returned, gives the restaurant its fee back once, and Costonomy bears the courier")
    void cancelAndRefundAfterPickupRefundsOnceAndTheCourierStaysOwed() throws Exception {
        var failed = failedAtHandOver();
        BigDecimal held = failed.netHeld();
        assertThat(held).isGreaterThan(BigDecimal.ZERO);
        BigDecimal balance = wallet.balanceOf(outletId);

        resolve(adminToken, failed.getId(), "CANCEL_AND_REFUND", "Customer never took the food")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RETURNED"));
        resolve(adminToken, failed.getId(), "CANCEL_AND_REFUND", "Again").andExpect(status().isOk());

        var after = reload(failed.getId());
        assertThat(after.getStatus()).isEqualTo(FoodDeliveryStatus.RETURNED);
        assertThat(after.isReconciliationRequired()).isFalse();
        assertThat(after.netHeld()).isEqualByComparingTo("0");
        assertThat(wallet.balanceOf(outletId).subtract(balance)).as("the fee, once").isEqualByComparingTo(held);
        assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where kind = 'FOOD_DELIVERY_REFUND' "
                + "and reference = ?", Integer.class, "food-delivery-" + failed.getId() + "-refund")).isEqualTo(1);

        var payable = jdbc.queryForMap("select state, amount_due, buyer_contribution, platform_funded from courier_payable "
                + "where delivery_id = ? and source_type = 'FOOD_DELIVERY'", failed.getId());
        assertThat(payable.get("state")).as("the courier is still owed").isEqualTo("DUE");
        assertThat((BigDecimal) payable.get("amount_due")).isEqualByComparingTo("80.00");
        assertThat((BigDecimal) payable.get("buyer_contribution")).as("the restaurant bears none of it").isEqualByComparingTo("0");
        assertThat((BigDecimal) payable.get("platform_funded")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("MARK_DELIVERED after a failed hand-over completes it and clears the flag; before any courier had the food it is refused")
    void markDeliveredNeedsACourierWhoHadTheFood() throws Exception {
        var failed = failedAtHandOver();
        resolve(adminToken, failed.getId(), "MARK_DELIVERED", "Restaurant confirmed the customer got it")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DELIVERED"));
        var after = reload(failed.getId());
        assertThat(after.isReconciliationRequired()).isFalse();
        assertThat(after.getDeliveredAt()).isNotNull();
        assertThat(foodPayables(failed.getId()).get(0)).containsEntry("state", "DUE").containsEntry("due_reason", "DELIVERED");

        var fresh = bookingService.create(request());
        resolve(adminToken, fresh.getId(), "MARK_DELIVERED", "No courier had it").andExpect(status().isConflict());
        assertThat(reload(fresh.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
    }

    @Test
    @DisplayName("CANCEL_AND_REFUND before pickup cancels, stands the courier down and returns the hold, once")
    void cancelAndRefundBeforePickup() throws Exception {
        var d = bookingService.create(request());
        BigDecimal balance = wallet.balanceOf(outletId);
        BigDecimal held = reload(d.getId()).netHeld();

        resolve(adminToken, d.getId(), "CANCEL_AND_REFUND", "Restaurant closed").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        resolve(adminToken, d.getId(), "CANCEL_AND_REFUND", "Again").andExpect(status().isOk());

        assertThat(wallet.balanceOf(outletId).subtract(balance)).isEqualByComparingTo(held);
        assertThat(foodPayables(d.getId())).noneMatch(row -> "DUE".equals(row.get("state")));
    }

    @Test
    @DisplayName("only operations may resolve, a reason is required, and an unknown outcome is refused")
    void resolveIsGuarded() throws Exception {
        var failed = failedAtHandOver();
        String restaurantToken = new ApiClient(mvc, json).loginFresh();

        resolve(restaurantToken, failed.getId(), "CLEAR_FLAG", "I am the restaurant").andExpect(status().is4xxClientError());
        resolve(adminToken, failed.getId(), "CLEAR_FLAG", " ").andExpect(status().isBadRequest());
        resolve(adminToken, failed.getId(), "NOT_AN_OUTCOME", "x").andExpect(status().isBadRequest());

        assertThat(reload(failed.getId()).isReconciliationRequired()).isTrue();
    }

    @Test
    @DisplayName("food courier payables are rebuilt by the same cursor and backfill: more than one batch, rows beyond any lookback")
    void foodPayablesAreRebuiltByTheCursorAndBackfill() {
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            ids.add(bookingService.create(request()).getId());
        }
        // Lose their payables, and make one of the deliveries older than any lookback.
        for (long id : ids) {
            jdbc.update("delete e from courier_payable_event e join courier_payable p on p.id = e.payable_id "
                    + "where p.source_type = 'FOOD_DELIVERY' and p.delivery_id = ?", id);
            jdbc.update("delete a from courier_payment_attempt a join courier_payable p on p.id = a.payable_id "
                    + "where p.source_type = 'FOOD_DELIVERY' and p.delivery_id = ?", id);
            jdbc.update("delete from courier_payable where source_type = 'FOOD_DELIVERY' and delivery_id = ?", id);
        }
        jdbc.update("update food_delivery set updated_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(90L * 86_400)), ids.get(0));
        assertThat(payableSyncService.audit().missingPayables()).as("the audit sees them").isGreaterThanOrEqualTo(3);

        var original = new int[] {payableProps.getSyncBatch(), payableProps.getSyncMaxBatchesPerPass()};
        var lag = payableProps.getSyncSettleLag();
        try {
            payableProps.setSyncSettleLag(java.time.Duration.ZERO);
            payableProps.setSyncBatch(2);                      // fewer than the rows to reach: it takes several batches
            payableProps.setSyncMaxBatchesPerPass(3);
            payableSyncService.rewind("FOOD_DELIVERY");
            int guard = 0;
            while (!ids.stream().allMatch(id -> !foodPayables(id).isEmpty()) && guard++ < 200) {
                payableSyncService.run();
            }
        } finally {
            payableProps.setSyncBatch(original[0]);
            payableProps.setSyncMaxBatchesPerPass(original[1]);
            payableProps.setSyncSettleLag(lag);
        }

        assertThat(ids).allSatisfy(id -> assertThat(foodPayables(id)).as("food delivery %s", id).hasSize(1));

        // And the backfill path, for rows the cursor was never going to show again.
        for (long id : ids) {
            jdbc.update("delete e from courier_payable_event e join courier_payable p on p.id = e.payable_id "
                    + "where p.source_type = 'FOOD_DELIVERY' and p.delivery_id = ?", id);
            jdbc.update("delete from courier_payable where source_type = 'FOOD_DELIVERY' and delivery_id = ?", id);
        }
        long after = 0;
        com.costonomy.mp.delivery.service.CourierPayableSyncService.Backfill page;
        int pages = 0;
        do {
            page = payableSyncService.backfill("FOOD_DELIVERY", after, 2);
            after = page.nextAfterId();
        } while (page.remaining() > 0 && pages++ < 200);
        assertThat(ids).allSatisfy(id -> assertThat(foodPayables(id)).hasSize(1));
    }

    @Test
    @DisplayName("Cancellation echo: provider CANCELLED after delivery already cancelled is applied without duplicate refund")
    void cancellationEcho_appliedWithoutDuplicateRefund() {
        var d = bookingService.create(request());
        bookingService.cancel(d.getId(), "Customer cancelled");

        var reloaded = reload(d.getId());
        assertThat(reloaded.getStatus()).isEqualTo(FoodDeliveryStatus.CANCELLED);
        BigDecimal refundedBefore = reloaded.getFeeRefundedAmount();

        // Provider sends CANCELLED echo
        var echoEvent = new DeliveryProvider.ProviderEvent(
                "evt-echo-1",
                DeliveryProvider.ProviderDeliveryStatus.CANCELLED,
                "Cancelled as requested",
                Instant.now()
        );

        String disposition = eventService.apply(reloaded, echoEvent, reloaded.getProviderCode(), null);
        assertThat(disposition).isEqualTo("APPLIED");

        var afterEcho = reload(d.getId());
        assertThat(afterEcho.getStatus()).isEqualTo(FoodDeliveryStatus.CANCELLED);
        // Refunded amount did not increase
        assertThat(afterEcho.getFeeRefundedAmount()).isEqualByComparingTo(refundedBefore);
    }

    @Test
    @DisplayName("Duplicate events: second identical event returns DUPLICATE")
    void duplicateEvent_returnsDuplicate() {
        var d = bookingService.create(request());
        var event = new DeliveryProvider.ProviderEvent(
                "evt-dup-1",
                DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED,
                "Driver assigned",
                Instant.now()
        );

        String first = eventService.apply(d, event, d.getProviderCode(), null);
        assertThat(first).isEqualTo("APPLIED");

        String second = eventService.apply(d, event, d.getProviderCode(), null);
        assertThat(second).isEqualTo("DUPLICATE");
    }

    @Test
    @DisplayName("Stale consignment event from replaced provider delivery is IGNORED")
    void staleConsignmentEvent_isIgnored() {
        var d = bookingService.create(request());
        // Active provider delivery is current
        var event = new DeliveryProvider.ProviderEvent(
                "evt-stale-1",
                DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                "Picked up by old driver",
                Instant.now()
        );

        // Apply with a different / replaced providerDeliveryId
        String disposition = eventService.apply(d, d.getProviderCode(), "replaced-consignment-id-99", event, null);
        assertThat(disposition).isEqualTo("IGNORED");
    }
}
