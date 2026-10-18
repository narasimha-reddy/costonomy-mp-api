package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService;
import com.costonomy.mp.fooddelivery.service.FoodDeliveryBookingService.CreateRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Creating a food delivery, holding its fee, booking a courier with fallback, and ending it cleanly, against the real
 * database and wallet with the two mock couriers. MOCK_SAVER is the cheaper (about ₹49 over this short route) and
 * MOCK_EXPRESS the dearer (about ₹76), so the default fee ceiling of 1.5 x the cheapest (₹74) sits just under the dearer
 * one, which is what lets these tests show the cap working.
 */
@AutoConfigureMockMvc
class FoodDeliveryBookingIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FoodDeliveryBookingService service;
    @Autowired private FoodDeliveryJobs jobs;
    @Autowired private FoodDeliveryRepository deliveries;
    @Autowired private WalletService wallet;
    @Autowired private com.costonomy.mp.delivery.service.ReliabilityProperties reliability;

    @Autowired @Qualifier("mockExpressDeliveryProvider") private MockDeliveryProvider express;
    @Autowired @Qualifier("mockSaverDeliveryProvider") private MockDeliveryProvider saver;

    private long restaurantId;
    private long outletId;

    /**
     * Reliability is off here. The two mock couriers are shared with every other test in the run, and tests that arm
     * BOOKING_FAILS on them leave failed attempts behind, so by the time these run the cheaper one can look unreliable
     * and the dearer be asked first: the engine doing its job, and a test about booking made to depend on test order.
     * Ranking and the breaker are covered by FoodCourierSelectorTest, ReliabilityRankingTest and ProviderReliabilityServiceIT.
     */
    @BeforeEach
    void setUp() throws Exception {
        reliability.setEnabled(false);
        express.disarm();
        saver.disarm();
        var api = new ApiClient(mvc, json);
        JsonNode created = api.post(api.loginFresh(), "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12", "city", "Hyderabad",
                        "state", "Telangana", "pincode", "500034", "contactName", "Asha", "contactPhone", "+919876511111",
                        "latitude", "17.4156", "longitude", "78.4347"))).get("data");
        restaurantId = created.get("id").asLong();
        outletId = created.at("/outlets/0/id").asLong();
        wallet.topUp(outletId, new BigDecimal("1000"), "test");
    }

    @AfterEach
    void restoreReliability() {
        reliability.setEnabled(true);
        express.disarm();
        saver.disarm();
    }

    private CreateRequest request(String key, BigDecimal maxFee) {
        return new CreateRequest(restaurantId, outletId, key, "POS-77", "Asha", "+919876543210",
                "Flat 4B, Jubilee Hills", "Near the park", new BigDecimal("17.4326"), new BigDecimal("78.4071"),
                "Hyderabad", "Telangana", "500033", "Ring the bell twice",
                new BigDecimal("640"), new BigDecimal("2.5"), 45, maxFee);
    }

    private CreateRequest request() {
        return request(UUID.randomUUID().toString(), null);
    }

    private BigDecimal balance() {
        return wallet.balanceOf(outletId);
    }

    private List<Map<String, Object>> attempts(long id) {
        return jdbc.queryForList("select * from food_delivery_attempt where food_delivery_id = ? order by attempt_number", id);
    }

    private List<Map<String, Object>> walletEntries(long id) {
        return jdbc.queryForList("select kind, direction, amount, reference from wallet_transaction "
                + "where reference like ? order by id", "food-delivery-" + id + "%");
    }

    private FoodDelivery reload(long id) {
        return deliveries.findById(id).orElseThrow();
    }

    // ── The happy path ───────────────────────────────────────────────────

    @Test
    @DisplayName("a delivery is booked with the cheapest courier, the fee is held, and the decision is recorded")
    void booksTheCheapestAndHoldsTheFee() {
        BigDecimal before = balance();

        var d = service.create(request());

        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(d.getProviderCode()).isEqualTo("MOCK_SAVER");
        assertThat(d.getProviderDeliveryId()).isNotBlank();
        assertThat(d.getFee()).isPositive();
        assertThat(d.getMaxFee()).as("1.5 x the cheapest, rounded up to a rupee").isEqualByComparingTo(
                d.getFee().multiply(new BigDecimal("1.5")).setScale(0, java.math.RoundingMode.CEILING));
        assertThat(before.subtract(balance())).as("the whole maximum is held, not just the price").isEqualByComparingTo(d.getMaxFee());
        assertThat(d.getAssignmentDeadline()).isAfter(Instant.now());
        assertThat(d.getTrackingToken()).hasSize(32);

        var all = attempts(d.getId());
        assertThat(all).hasSize(1);
        assertThat(all.get(0)).containsEntry("outcome", "BOOKED").containsEntry("rank_position", 1);
        assertThat(all.get(0).get("selection_reason")).isIn("CHEAPEST", "ONLY_OPTION");
        assertThat(all.get(0).get("call_ms")).isNotNull();
        assertThat(all.get(0).get("decision")).isNotNull();

        assertThat(jdbc.queryForObject("select count(*) from food_delivery_quote where food_delivery_id = ? and selected = 1",
                Integer.class, d.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from food_delivery_event where food_delivery_id = ?",
                Integer.class, d.getId())).isGreaterThanOrEqualTo(3);
        assertThat(walletEntries(d.getId())).hasSize(1);
        assertThat(walletEntries(d.getId()).get(0)).containsEntry("kind", "FOOD_DELIVERY_FEE").containsEntry("direction", "DEBIT");
    }

    @Test
    @DisplayName("the same idempotency key returns the first delivery and books and charges nothing more")
    void replayIsIdempotent() {
        var req = request();
        var first = service.create(req);
        BigDecimal afterFirst = balance();

        var second = service.create(req);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(balance()).isEqualByComparingTo(afterFirst);
        assertThat(attempts(first.getId())).hasSize(1);
        assertThat(walletEntries(first.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a retry while initial creation is still in REQUESTED status returns the in-progress delivery")
    void retryWhileInProgressReturnsRequestedDelivery() {
        var key = UUID.randomUUID().toString();
        var req = request(key, null);

        var initial = new FoodDelivery();
        initial.setRestaurantId(restaurantId);
        initial.setOutletId(outletId);
        initial.setIdempotencyKey(key);
        initial.setStatus(FoodDeliveryStatus.REQUESTED);
        initial.setPickupAddress("Road No 12, Banjara Hills");
        initial.setCustomerName("Asha");
        initial.setCustomerPhone("+919876543210");
        initial.setDropAddress("Flat 4B, Jubilee Hills");
        initial.setRequestedAt(Instant.now());
        initial = deliveries.save(initial);

        BigDecimal before = balance();
        var result = service.create(req);

        assertThat(result.getId()).isEqualTo(initial.getId());
        assertThat(result.getStatus()).isEqualTo(FoodDeliveryStatus.REQUESTED);
        assertThat(balance()).isEqualByComparingTo(before);
    }

    // ── Refused before anything is charged ───────────────────────────────

    @Test
    @DisplayName("a wallet that cannot hold the fee refuses the request, and nothing is created or charged")
    void insufficientWallet() {
        jdbc.update("update wallet set balance = 5 where outlet_id = ?", outletId);
        var req = request();

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("wallet");

        assertThat(balance()).isEqualByComparingTo("5");
        assertThat(deliveries.findByRestaurantIdAndIdempotencyKey(restaurantId, req.idempotencyKey())).isEmpty();
    }

    @Test
    @DisplayName("a fee limit below the cheapest courier is refused with the real price, and nothing is charged")
    void feeLimitBelowEveryCourier() {
        BigDecimal before = balance();
        var req = request(UUID.randomUUID().toString(), new BigDecimal("10"));

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("cheapest delivery partner costs").hasMessageContaining("limit of ₹10");

        assertThat(balance()).isEqualByComparingTo(before);
        assertThat(deliveries.findByRestaurantIdAndIdempotencyKey(restaurantId, req.idempotencyKey())).isEmpty();
    }

    @Test
    @DisplayName("no courier covering the address means no delivery, no hold")
    void nobodyCoversIt() {
        express.arm(MockDeliveryProvider.Failure.QUOTE_FAILS);
        saver.arm(MockDeliveryProvider.Failure.QUOTE_FAILS);
        BigDecimal before = balance();

        assertThatThrownBy(() -> service.create(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.DELIVERY_UNAVAILABLE));

        assertThat(balance()).isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("another restaurant's outlet is reported as not found")
    void wrongRestaurant() {
        var req = new CreateRequest(restaurantId + 999_999, outletId, UUID.randomUUID().toString(), null, "Asha",
                "+919876543210", "Flat 4B", null, new BigDecimal("17.4326"), new BigDecimal("78.4071"),
                "Hyderabad", "Telangana", "500033", null, new BigDecimal("640"), new BigDecimal("2.5"), 45, null);

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a request missing what couriers need is refused by name")
    void validation() {
        var noCity = new CreateRequest(restaurantId, outletId, UUID.randomUUID().toString(), null, "Asha", "+919876543210",
                "Flat 4B", null, new BigDecimal("17.4326"), new BigDecimal("78.4071"), " ", "Telangana", "500033", null,
                new BigDecimal("640"), new BigDecimal("2.5"), 45, null);
        var tooHeavy = new CreateRequest(restaurantId, outletId, UUID.randomUUID().toString(), null, "Asha", "+919876543210",
                "Flat 4B", null, new BigDecimal("17.4326"), new BigDecimal("78.4071"), "Hyderabad", "Telangana", "500033", null,
                new BigDecimal("640"), new BigDecimal("80"), 45, null);

        assertThatThrownBy(() -> service.create(noCity)).hasMessageContaining("city, state and pincode");
        assertThatThrownBy(() -> service.create(tooHeavy)).hasMessageContaining("bike can carry");
    }

    // ── Fallback ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a courier that refuses is skipped and the next one books, within the agreed maximum")
    void refusalFallsBackToTheNextCourier() {
        saver.arm(MockDeliveryProvider.Failure.BOOKING_FAILS);

        var d = service.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));

        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(d.getProviderCode()).isEqualTo("MOCK_EXPRESS");
        var all = attempts(d.getId());
        assertThat(all).hasSize(2);
        assertThat(all.get(0)).containsEntry("provider_code", "MOCK_SAVER").containsEntry("outcome", "FAILED")
                .containsEntry("ended_cause", "PROVIDER_REFUSED");
        assertThat(all.get(1)).containsEntry("provider_code", "MOCK_EXPRESS").containsEntry("outcome", "BOOKED");
        assertThat(d.getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a dearer courier is never asked past the agreed maximum, even when it is the only one left")
    void capStopsADearerFallback() {
        saver.arm(MockDeliveryProvider.Failure.BOOKING_FAILS);

        // The default maximum is 1.5 x the cheapest courier, which is just under the express courier's price.
        var d = service.create(request());

        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_UNAVAILABLE);
        assertThat(attempts(d.getId())).extracting(a -> a.get("provider_code")).containsExactly("MOCK_SAVER");
        assertThat(d.getProviderDeliveryId()).isNull();
        assertThat(reload(d.getId()).netHeld()).as("the hold stays while the retry window runs").isPositive();
    }

    // ── The retry window ─────────────────────────────────────────────────

    @Test
    @DisplayName("when nobody takes it the delivery waits, and the retry asks everyone again once a courier is back")
    void retryWithinTheWindow() {
        saver.arm(MockDeliveryProvider.Failure.BOOKING_FAILS);
        express.arm(MockDeliveryProvider.Failure.BOOKING_FAILS);
        var d = service.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));
        assertThat(d.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_UNAVAILABLE);
        assertThat(attempts(d.getId())).hasSize(2);

        saver.disarm();
        express.disarm();
        jobs.runRetry();

        var after = reload(d.getId());
        assertThat(after.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(after.getProviderCode()).as("couriers from the failed round are asked again").isEqualTo("MOCK_SAVER");
        assertThat(attempts(d.getId())).hasSize(3);
        assertThat(attempts(d.getId()).get(2)).containsEntry("attempt_type", "RETRY");
    }

    @Test
    @DisplayName("past the retry window the delivery ends FAILED, the whole hold is returned, and a repeat pays nothing twice")
    void failsAfterTheWindowAndRefunds() {
        BigDecimal before = balance();
        saver.arm(MockDeliveryProvider.Failure.BOOKING_FAILS);
        express.arm(MockDeliveryProvider.Failure.BOOKING_FAILS);
        var d = service.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));
        assertThat(balance()).isLessThan(before);

        jdbc.update("update food_delivery set requested_at = utc_timestamp(6) - interval 10 minute where id = ?", d.getId());
        jobs.runRetry();
        jobs.runRetry();

        var failed = reload(d.getId());
        assertThat(failed.getStatus()).isEqualTo(FoodDeliveryStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("NO_COURIER");
        assertThat(failed.getFee()).isEqualByComparingTo("0");
        assertThat(failed.netHeld()).isEqualByComparingTo("0");
        assertThat(balance()).as("every rupee back").isEqualByComparingTo(before);
        assertThat(walletEntries(d.getId())).extracting(e -> e.get("kind"))
                .containsExactly("FOOD_DELIVERY_FEE", "FOOD_DELIVERY_REFUND");
    }

    // ── Cancelling and settling ──────────────────────────────────────────

    @Test
    @DisplayName("cancelling before pickup stands the courier down and returns the whole hold, once")
    void cancelBeforePickup() {
        BigDecimal before = balance();
        var d = service.create(request());

        var cancelled = service.cancel(d.getId(), "Customer changed their mind");
        service.cancel(d.getId(), "Again");

        assertThat(cancelled.getStatus()).isEqualTo(FoodDeliveryStatus.CANCELLED);
        assertThat(balance()).isEqualByComparingTo(before);
        assertThat(attempts(d.getId()).get(0)).containsEntry("outcome", "CANCELLED").containsEntry("ended_cause", "CUSTOMER_CANCEL");
        assertThat(walletEntries(d.getId())).hasSize(2);
        assertThat(reload(d.getId()).getProviderDeliveryId()).isNull();
    }

    @Test
    @DisplayName("once the food is picked up it cannot be cancelled")
    void cannotCancelAfterPickup() {
        var d = service.create(request());
        jdbc.update("update food_delivery set status = 'OUT_FOR_DELIVERY' where id = ?", d.getId());

        assertThatThrownBy(() -> service.cancel(d.getId(), "Too late")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("after pickup the unused part of the hold is returned, once")
    void settleReturnsTheUnusedPart() {
        BigDecimal before = balance();
        var d = service.create(request());
        jdbc.update("update food_delivery set status = 'PICKED_UP' where id = ?", d.getId());
        var booked = reload(d.getId());

        BigDecimal returned = service.settleFee(d.getId());
        BigDecimal again = service.settleFee(d.getId());

        assertThat(returned).isEqualByComparingTo(booked.getMaxFee().subtract(booked.getFee()));
        assertThat(again).isZero();
        assertThat(before.subtract(balance())).as("the restaurant pays the courier's price and no more")
                .isEqualByComparingTo(booked.getFee());
    }

    @Test
    @DisplayName("the fee is not settled before the food is picked up")
    void cannotSettleEarly() {
        var d = service.create(request());

        assertThatThrownBy(() -> service.settleFee(d.getId())).isInstanceOf(BusinessException.class);
    }

    // ── A courier that never sends a rider ───────────────────────────────

    @Test
    @DisplayName("a booking with no rider past its deadline goes to the other courier, which is recorded as a timeout")
    void assignmentTimeoutRebooks() {
        var d = service.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));
        assertThat(d.getProviderCode()).isEqualTo("MOCK_SAVER");
        jdbc.update("update food_delivery set assignment_deadline = utc_timestamp(6) - interval 5 minute where id = ?", d.getId());

        int moved = jobs.runSweep(Instant.now());

        assertThat(moved).isEqualTo(1);
        var after = reload(d.getId());
        assertThat(after.getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        assertThat(after.getProviderCode()).isEqualTo("MOCK_EXPRESS");
        var all = attempts(d.getId());
        assertThat(all).hasSize(2);
        assertThat(all.get(0)).containsEntry("outcome", "CANCELLED").containsEntry("ended_cause", "ASSIGNMENT_TIMEOUT");
        assertThat(all.get(1)).containsEntry("attempt_type", "REASSIGNMENT");
        assertThat(after.getFee()).as("the fee follows the new courier").isGreaterThan(d.getFee());
        assertThat(walletEntries(d.getId())).as("no second debit for the dearer courier").hasSize(1);
    }

    @Test
    @DisplayName("a booking whose deadline has not passed is left alone")
    void sweepLeavesLiveBookings() {
        var d = service.create(request());

        assertThat(jobs.runSweep(Instant.now())).isZero();
        assertThat(attempts(d.getId())).hasSize(1);
    }

    @Test
    @DisplayName("an event about a courier the delivery has already left changes nothing")
    void staleStandDownIsIgnored() {
        var d = service.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));

        assertThat(service.rebook(d.getId(), "some-old-consignment", "RIDER_CANCELLED", "x")).isFalse();
        assertThat(attempts(d.getId())).hasSize(1);
        assertThat(reload(d.getId()).getProviderCode()).isEqualTo("MOCK_SAVER");
    }

    @Test
    @DisplayName("a rider cancelling hands it to the other courier and records who let go")
    void riderCancelledRebooks() {
        var d = service.create(request(UUID.randomUUID().toString(), new BigDecimal("200")));

        boolean booked = service.rebook(d.getId(), d.getProviderDeliveryId(), "RIDER_CANCELLED", "Bike broke down");

        assertThat(booked).isTrue();
        assertThat(reload(d.getId()).getProviderCode()).isEqualTo("MOCK_EXPRESS");
        assertThat(attempts(d.getId()).get(0)).containsEntry("ended_cause", "RIDER_CANCELLED");
        assertThat(attempts(d.getId()).get(1).get("selection_reason")).isIn("RELIABILITY", "ONLY_OPTION");
    }

    @Test
    @DisplayName("if nobody else can take it after a rider let go, it waits for the retry window like any other")
    void rebookWithNobodyLeft() {
        var d = service.create(request());

        boolean booked = service.rebook(d.getId(), d.getProviderDeliveryId(), "RIDER_CANCELLED", "Bike broke down");

        // The default maximum excludes the dearer courier, and the first has already been tried this round.
        assertThat(booked).isFalse();
        assertThat(reload(d.getId()).getStatus()).isEqualTo(FoodDeliveryStatus.PROVIDER_UNAVAILABLE);
    }
}
