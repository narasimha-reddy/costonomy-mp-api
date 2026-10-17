package com.costonomy.mp.fooddelivery;

import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryAttempt;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryEvent;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryLocation;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryQuote;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryAttemptRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryEventRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryLocationRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryQuoteRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The food delivery mapping against the real V88 schema (Hibernate runs with {@code ddl-auto=validate}, so a
 * column drift fails the context), and the guarantees the database is meant to give: one delivery per idempotency
 * key, one per tracking token, and no duplicate provider events.
 */
@AutoConfigureMockMvc
class FoodDeliveryRepositoryIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FoodDeliveryRepository deliveries;
    @Autowired private FoodDeliveryQuoteRepository quotes;
    @Autowired private FoodDeliveryAttemptRepository attempts;
    @Autowired private FoodDeliveryEventRepository events;
    @Autowired private FoodDeliveryLocationRepository locations;
    @Autowired private com.costonomy.mp.delivery.service.DeliveryProviderRegistry registry;

    private long restaurantId;
    private long outletId;

    @BeforeEach
    void restaurant() throws Exception {
        var api = new ApiClient(mvc, json);
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of(
                        "name", "Banjara Hills", "addressLine1", "Road No 12", "city", "Hyderabad",
                        "state", "Telangana", "pincode", "500034",
                        "latitude", "17.4156", "longitude", "78.4347"))).get("data");
        restaurantId = created.get("id").asLong();
        outletId = created.at("/outlets/0/id").asLong();
    }

    private FoodDelivery newDelivery(String key) {
        var d = new FoodDelivery();
        d.setRestaurantId(restaurantId);
        d.setOutletId(outletId);
        d.setIdempotencyKey(key);
        d.setExternalOrderRef("POS-1001");
        d.setPickupAddress("Road No 12, Banjara Hills, Hyderabad");
        d.setPickupLatitude(new BigDecimal("17.4156000"));
        d.setPickupLongitude(new BigDecimal("78.4347000"));
        d.setCustomerName("Asha");
        d.setCustomerPhone("+919876543210");
        d.setDropAddress("Flat 4B, Jubilee Hills, Hyderabad");
        d.setDropLatitude(new BigDecimal("17.4326000"));
        d.setDropLongitude(new BigDecimal("78.4071000"));
        d.setOrderValue(new BigDecimal("640.0000"));
        d.setRequestedAt(Instant.now());
        return d;
    }

    @Test
    @DisplayName("a food delivery round-trips with its defaults, token and version")
    void roundTrip() {
        var saved = deliveries.saveAndFlush(newDelivery(UUID.randomUUID().toString()));

        var loaded = deliveries.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(FoodDeliveryStatus.REQUESTED);
        assertThat(loaded.getFee()).isEqualByComparingTo("0");
        assertThat(loaded.getCurrency()).isEqualTo("INR");
        assertThat(loaded.getTrackingToken()).matches("[0-9a-f]{32}");
        assertThat(loaded.getVersion()).isNotNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getDropLatitude()).isEqualByComparingTo("17.4326");
    }

    @Test
    @DisplayName("the same idempotency key from the same restaurant cannot make a second delivery")
    void idempotencyKeyIsUnique() {
        String key = UUID.randomUUID().toString();
        var first = deliveries.saveAndFlush(newDelivery(key));

        assertThat(deliveries.findByRestaurantIdAndIdempotencyKey(restaurantId, key)).get()
                .extracting(FoodDelivery::getId).isEqualTo(first.getId());
        assertThat(deliveries.findByRestaurantIdAndIdempotencyKey(restaurantId, "other")).isEmpty();

        assertThatThrownBy(() -> deliveries.saveAndFlush(newDelivery(key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a customer finds their delivery by token, and two deliveries cannot share one")
    void trackingTokenLookupAndUniqueness() {
        var a = deliveries.saveAndFlush(newDelivery(UUID.randomUUID().toString()));

        assertThat(deliveries.findByTrackingToken(a.getTrackingToken())).get()
                .extracting(FoodDelivery::getId).isEqualTo(a.getId());
        assertThat(deliveries.findByTrackingToken("0".repeat(32))).isEmpty();

        var b = newDelivery(UUID.randomUUID().toString());
        b.setTrackingToken(a.getTrackingToken());
        assertThatThrownBy(() -> deliveries.saveAndFlush(b)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a provider's own id finds the delivery; outlet and restaurant lists are newest first")
    void lookups() {
        var older = newDelivery(UUID.randomUUID().toString());
        older.setProviderCode("MOCK_SAVER");
        older.setProviderDeliveryId("PD-" + UUID.randomUUID());
        older = deliveries.saveAndFlush(older);
        // utc_timestamp, not a Java Timestamp: Hibernate stores UTC, and a JDBC Timestamp would be written in this
        // machine's zone and sort wrongly against it (D-185).
        jdbc.update("update food_delivery set created_at = utc_timestamp(6) - interval 1 hour where id = ?",
                older.getId());
        var newer = deliveries.saveAndFlush(newDelivery(UUID.randomUUID().toString()));

        assertThat(deliveries.findByProviderCodeAndProviderDeliveryId("MOCK_SAVER", older.getProviderDeliveryId()))
                .get().extracting(FoodDelivery::getId).isEqualTo(older.getId());
        assertThat(deliveries.findByOutletIdOrderByCreatedAtDesc(outletId, PageRequest.of(0, 10)))
                .extracting(FoodDelivery::getId).containsExactly(newer.getId(), older.getId());
        assertThat(deliveries.findByRestaurantIdOrderByCreatedAtDesc(restaurantId, PageRequest.of(0, 1)))
                .extracting(FoodDelivery::getId).containsExactly(newer.getId());
    }

    @Test
    @DisplayName("the waterfall sweep finds only unassigned bookings past their deadline")
    void assignmentDeadlineSweep() {
        var late = newDelivery(UUID.randomUUID().toString());
        late.setStatus(FoodDeliveryStatus.PROVIDER_SELECTED);
        late.setAssignmentDeadline(Instant.now().minus(1, ChronoUnit.MINUTES));
        late = deliveries.saveAndFlush(late);

        var notYet = newDelivery(UUID.randomUUID().toString());
        notYet.setStatus(FoodDeliveryStatus.PROVIDER_SELECTED);
        notYet.setAssignmentDeadline(Instant.now().plus(5, ChronoUnit.MINUTES));
        deliveries.saveAndFlush(notYet);

        var assigned = newDelivery(UUID.randomUUID().toString());
        assigned.setStatus(FoodDeliveryStatus.DRIVER_ASSIGNED);
        assigned.setAssignmentDeadline(Instant.now().minus(1, ChronoUnit.MINUTES));
        deliveries.saveAndFlush(assigned);

        assertThat(deliveries.findByStatusAndAssignmentDeadlineBefore(
                FoodDeliveryStatus.PROVIDER_SELECTED, Instant.now()))
                .extracting(FoodDelivery::getId).contains(late.getId())
                .doesNotContain(notYet.getId(), assigned.getId());
    }

    @Test
    @DisplayName("quotes, attempts, events and locations hang off the delivery and read back in order")
    void children() {
        var d = deliveries.saveAndFlush(newDelivery(UUID.randomUUID().toString()));
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        for (String code : List.of("PIDGE", "BORZO")) {
            var q = new FoodDeliveryQuote();
            q.setFoodDeliveryId(d.getId());
            q.setProviderCode(code);
            q.setStatus("QUOTED");
            q.setAmount(new BigDecimal("45.0000"));
            q.setSelected("BORZO".equals(code));
            quotes.save(q);
        }
        assertThat(quotes.findByFoodDeliveryIdOrderByIdAsc(d.getId()))
                .extracting(FoodDeliveryQuote::getProviderCode).containsExactly("PIDGE", "BORZO");

        for (int n = 1; n <= 2; n++) {
            var a = new FoodDeliveryAttempt();
            a.setFoodDeliveryId(d.getId());
            a.setProviderCode(n == 1 ? "PIDGE" : "BORZO");
            a.setAttemptNumber(n);
            a.setOutcome(n == 1 ? "FAILED" : "BOOKED");
            a.setStartedAt(t0);
            attempts.save(a);
        }
        assertThat(attempts.findFirstByFoodDeliveryIdOrderByAttemptNumberDesc(d.getId())).get()
                .extracting(FoodDeliveryAttempt::getProviderCode).isEqualTo("BORZO");

        var e = new FoodDeliveryEvent();
        e.setFoodDeliveryId(d.getId());
        e.setEventType("FoodDeliveryDriverAssigned");
        e.setStatus(FoodDeliveryStatus.DRIVER_ASSIGNED);
        e.setProviderCode("BORZO");
        e.setProviderEventId("evt-1");
        e.setPayload("{\"k\":1}");
        e.setOccurredAt(t0);
        events.saveAndFlush(e);
        assertThat(events.existsByProviderCodeAndProviderEventId("BORZO", "evt-1")).isTrue();
        assertThat(events.existsByProviderCodeAndProviderEventId("BORZO", "evt-2")).isFalse();
        assertThat(events.findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(d.getId()))
                .extracting(FoodDeliveryEvent::getStatus).containsExactly(FoodDeliveryStatus.DRIVER_ASSIGNED);

        for (int i = 0; i < 3; i++) {
            var l = new FoodDeliveryLocation();
            l.setFoodDeliveryId(d.getId());
            l.setLatitude(new BigDecimal("17.40").add(new BigDecimal(i).movePointLeft(3)));
            l.setLongitude(new BigDecimal("78.40"));
            l.setRecordedAt(t0.plusSeconds(i * 10L));
            locations.save(l);
        }
        assertThat(locations.findFirstByFoodDeliveryIdOrderByRecordedAtDescIdDesc(d.getId())).get()
                .extracting(FoodDeliveryLocation::getLatitude).satisfies(lat -> assertThat(lat).isEqualByComparingTo("17.402"));
    }

    @Test
    @DisplayName("a duplicate provider event id is refused by the database")
    void duplicateProviderEventRefused() {
        var d = deliveries.saveAndFlush(newDelivery(UUID.randomUUID().toString()));
        String eventId = "evt-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            var e = new FoodDeliveryEvent();
            e.setFoodDeliveryId(d.getId());
            e.setEventType("FoodDeliveryPickedUp");
            e.setProviderCode("PIDGE");
            e.setProviderEventId(eventId);
            e.setOccurredAt(Instant.now());
            if (i == 0) {
                events.saveAndFlush(e);
            } else {
                assertThatThrownBy(() -> events.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
            }
        }
    }

    @Test
    @DisplayName("only food-capable providers are flagged, and parcel carriers are not")
    void providerFlag() {
        List<String> flagged = jdbc.queryForList(
                "select code from delivery_provider where supports_food_delivery = 1", String.class);
        assertThat(flagged).contains("PIDGE", "BORZO", "PORTER", "SHADOWFAX", "LOADSHARE", "MOCK_EXPRESS", "MOCK_SAVER")
                .doesNotContain("DELHIVERY", "XPRESSBEES", "SHIPROCKET", "BLOWHORN");
    }

    @Test
    @DisplayName("the registry offers a food delivery only providers flagged for food, and the full list is unchanged")
    void registryFoodFilter() {
        var food = registry.enabledForFood().stream().map(a -> a.record().getCode()).toList();
        var all = registry.enabled().stream().map(a -> a.record().getCode()).toList();

        assertThat(food).contains("MOCK_EXPRESS", "MOCK_SAVER")
                .doesNotContain("DELHIVERY", "XPRESSBEES", "SHIPROCKET", "BLOWHORN");
        assertThat(all).containsAll(food);
        assertThat(all.size()).as("supplier delivery still sees every enabled provider").isGreaterThanOrEqualTo(food.size());
    }
}
