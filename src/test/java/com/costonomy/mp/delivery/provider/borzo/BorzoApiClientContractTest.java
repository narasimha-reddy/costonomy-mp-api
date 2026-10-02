package com.costonomy.mp.delivery.provider.borzo;

import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises {@link BorzoApiClient} against response bodies captured verbatim
 * from the live sandbox ({@code robotapitest-in.borzodelivery.com}), plus
 * deliberately broken variants of them — proving the fail-loud parsing
 * actually fails loud, rather than silently defaulting the way
 * {@code PidgeApiClient}'s untested contract does.
 */
class BorzoApiClientContractTest {

    private BorzoProperties properties;
    private MockRestServiceServer server;
    private BorzoApiClient client;

    private static final String BASE_URL = "https://robotapitest-in.borzodelivery.com/api/business/1.8";

    @BeforeEach
    void setUp() {
        properties = new BorzoProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setAuthToken("test-token");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new BorzoApiClient(properties, builder, new ObjectMapper());
        server = customizer.getServer();
    }

    private DeliveryProvider.QuoteRequest quoteRequest() {
        return new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9783692"), new BigDecimal("77.6408356"),
                new BigDecimal("12.9352403"), new BigDecimal("77.624532"),
                new BigDecimal("500.00"), null, null,
                null, null, VehicleType.TWO_WHEELER,
                "Indiranagar, Bengaluru, Karnataka, India",
                "Koramangala, Bengaluru, Karnataka, India");
    }

    private DeliveryProvider.BookingRequest bookingRequest() {
        return new DeliveryProvider.BookingRequest(
                101L, "borzo_q_ignored",
                "Indiranagar, Bengaluru, Karnataka, India",
                new BigDecimal("12.9783692"), new BigDecimal("77.6408356"),
                "Pickup Test", "+919876500000",
                "Koramangala, Bengaluru, Karnataka, India",
                new BigDecimal("12.9352403"), new BigDecimal("77.624532"),
                "Drop Test", "+919876500001",
                "mp-delivery-9-1");
    }

    // ── calculate-order: real captured sandbox response ──────────────────

    @Test
    @DisplayName("parses a real captured calculate-order response, deriving ETA from required_finish_datetime")
    void parsesRealCalculateOrderResponse() {
        String future = OffsetDateTime.now().plusMinutes(45).toString();
        String responseBody = """
                {
                  "is_successful": true,
                  "order": {
                    "order_id": null,
                    "points": [
                      {"address": "Indiranagar, Bengaluru, Karnataka, India",
                       "previous_point_driving_distance_meters": 0,
                       "required_finish_datetime": "%s"},
                      {"address": "Koramangala, Bengaluru, Karnataka, India",
                       "previous_point_driving_distance_meters": 6029,
                       "required_finish_datetime": "%s"}
                    ],
                    "payment_amount": "85.76",
                    "delivery_fee_amount": "85.76"
                  },
                  "warnings": [],
                  "parameter_warnings": null
                }
                """.formatted(future, future);

        server.expect(requestTo(BASE_URL + "/calculate-order"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var quote = client.calculateOrder(quoteRequest());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("85.76");
        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.etaMinutes()).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(45);
        assertThat(quote.distanceKm()).isEqualTo(6.029);
    }

    // ── calculate-order: the real "is_successful:true but unusable" failure mode ──

    @Test
    @DisplayName("fails loud, not unserviceable-silent, when Borzo answers is_successful:true with an empty points array")
    void failsLoudOnSuccessfulButEmptyPoints() {
        // Captured shape: a rejected point (e.g. missing address server-side)
        // comes back exactly like this — is_successful stays true.
        String responseBody = """
                {
                  "is_successful": true,
                  "order": { "order_id": null, "points": [], "payment_amount": null, "delivery_fee_amount": null },
                  "warnings": ["invalid_parameters"],
                  "parameter_warnings": {"points": [{"address": ["required"]}]}
                }
                """;

        server.expect(requestTo(BASE_URL + "/calculate-order"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.calculateOrder(quoteRequest()))
                .isInstanceOf(BorzoContractException.class)
                .hasMessageContaining("no points");
    }

    @Test
    @DisplayName("fails loud rather than defaulting a fake fare, when payment_amount is missing")
    void failsLoudOnMissingPaymentAmount() {
        String future = OffsetDateTime.now().plusMinutes(30).toString();
        String responseBody = """
                {
                  "is_successful": true,
                  "order": {
                    "order_id": null,
                    "points": [
                      {"address": "A", "previous_point_driving_distance_meters": 0, "required_finish_datetime": "%s"},
                      {"address": "B", "previous_point_driving_distance_meters": 1000, "required_finish_datetime": "%s"}
                    ],
                    "delivery_fee_amount": "40.00"
                  },
                  "warnings": []
                }
                """.formatted(future, future);

        server.expect(requestTo(BASE_URL + "/calculate-order"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.calculateOrder(quoteRequest()))
                .isInstanceOf(BorzoContractException.class)
                .hasMessageContaining("payment_amount");
    }

    @Test
    @DisplayName("declines rather than calling Borzo, when no address text is available")
    void declinesWithoutCallingBorzoWhenAddressMissing() {
        var request = new DeliveryProvider.QuoteRequest(
                101L, new BigDecimal("12.97"), new BigDecimal("77.64"),
                new BigDecimal("12.93"), new BigDecimal("77.62"),
                new BigDecimal("500.00"), null, null,
                null, null, VehicleType.TWO_WHEELER, null, null);

        var quote = client.calculateOrder(request);

        assertThat(quote.serviceable()).isFalse();
        server.verify(); // no HTTP call was made
    }

    @Test
    @DisplayName("declines an unverified vehicle type rather than guessing a vehicle_type_id")
    void declinesUnverifiedVehicleType() {
        var request = new DeliveryProvider.QuoteRequest(
                101L, new BigDecimal("12.97"), new BigDecimal("77.64"),
                new BigDecimal("12.93"), new BigDecimal("77.62"),
                new BigDecimal("500.00"), null, null,
                null, null, VehicleType.THREE_WHEELER,
                "A", "B");

        var quote = client.calculateOrder(request);

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("vehicle_type_id");
        server.verify();
    }

    // ── create-order: real captured sandbox response ──────────────────────

    @Test
    @DisplayName("parses a real captured create-order response")
    void parsesRealCreateOrderResponse() {
        String future = OffsetDateTime.now().plusMinutes(50).toString();
        String responseBody = """
                {
                  "is_successful": true,
                  "order": {
                    "order_id": 331900,
                    "points": [
                      {"address": "Indiranagar, Bengaluru, Karnataka, India", "tracking_url": "https://apitest.borzodelivery.com/in/track/PGA6GK33E6ZLIN",
                       "required_finish_datetime": "%s"},
                      {"address": "Koramangala, Bengaluru, Karnataka, India", "tracking_url": "https://apitest.borzodelivery.com/in/track/PGDPAED7INIHIN",
                       "required_finish_datetime": "%s", "delivery": {"status": "planned"}}
                    ],
                    "payment_amount": "85.76",
                    "delivery_fee_amount": "85.76"
                  },
                  "warnings": []
                }
                """.formatted(future, future);

        server.expect(requestTo(BASE_URL + "/create-order"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var booking = client.createOrder(bookingRequest());

        assertThat(booking.providerDeliveryId()).isEqualTo("331900");
        assertThat(booking.amount()).isEqualByComparingTo("85.76");
        assertThat(booking.trackingUrl()).isEqualTo("https://apitest.borzodelivery.com/in/track/PGDPAED7INIHIN");
        assertThat(booking.etaMinutes()).isNotNull().isGreaterThan(0);
    }

    @Test
    @DisplayName("fails loud rather than booking with no provider id, when order_id is missing")
    void failsLoudOnMissingOrderId() {
        String responseBody = """
                {
                  "is_successful": true,
                  "order": {
                    "order_id": null,
                    "points": [{"address": "A"}, {"address": "B"}],
                    "payment_amount": "85.76"
                  },
                  "warnings": []
                }
                """;

        server.expect(requestTo(BASE_URL + "/create-order"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createOrder(bookingRequest()))
                .isInstanceOf(BorzoContractException.class)
                .hasMessageContaining("order_id");
    }

    @Test
    @DisplayName("sends our own deterministic idempotency key as Borzo's client_order_id on every point")
    void sendsIdempotencyKeyAsClientOrderId() {
        String future = OffsetDateTime.now().plusMinutes(50).toString();
        String responseBody = """
                {
                  "is_successful": true,
                  "order": {
                    "order_id": 1,
                    "points": [
                      {"address": "A", "required_finish_datetime": "%s"},
                      {"address": "B", "required_finish_datetime": "%s"}
                    ],
                    "payment_amount": "10.00"
                  }
                }
                """.formatted(future, future);

        server.expect(requestTo(BASE_URL + "/create-order"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("mp-delivery-9-1")))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        client.createOrder(bookingRequest());
        server.verify();
    }

    // ── /orders status poll: real captured sandbox response shape ─────────

    @Test
    @DisplayName("maps a real captured status response's two-layer status")
    void parsesRealStatusResponse() {
        String responseBody = """
                {
                  "is_successful": true,
                  "order": {
                    "status": "active",
                    "points": [
                      {"address": "A"},
                      {"address": "B", "delivery": {"status": "planned"}}
                    ]
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/orders?order_id=331900"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("331900");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(delivery.driverName()).isNull();
        assertThat(delivery.events()).hasSize(1);
        var event = delivery.events().get(0);
        assertThat(event.providerEventId()).isEqualTo("borzo_331900_driver_assigned");
        assertThat(event.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
    }

    @Test
    @DisplayName("synthesizes newest-first DELIVERED and PICKED_UP events for completed status")
    void synthesizesEventsForCompletedStatus() {
        String responseBody = """
                {
                  "is_successful": true,
                  "order": {
                    "status": "completed",
                    "points": [
                      {"address": "A"},
                      {"address": "B", "delivery": {"status": "finished"}}
                    ]
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/orders?order_id=331900"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("331900");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events()).hasSize(2);
        assertThat(delivery.events().get(0).providerEventId()).isEqualTo("borzo_331900_delivered");
        assertThat(delivery.events().get(1).providerEventId()).isEqualTo("borzo_331900_picked_up");
    }

    @Test
    @DisplayName("fails loud rather than reporting PENDING by default, when order.status is missing")
    void failsLoudOnMissingStatus() {
        String responseBody = """
                { "is_successful": true, "order": { "points": [] } }
                """;

        server.expect(requestTo(BASE_URL + "/orders?order_id=331900"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getStatus("331900"))
                .isInstanceOf(BorzoContractException.class)
                .hasMessageContaining("status");
    }

    // ── cancel-order ────────────────────────────────────────────────────

    @Test
    @DisplayName("cancels by numeric order id, matching how /orders and /create-order represent it")
    void cancelsByNumericOrderId() {
        server.expect(requestTo(BASE_URL + "/cancel-order"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("331900")))
                .andRespond(withSuccess("{\"is_successful\": true}", MediaType.APPLICATION_JSON));

        client.cancel("331900", "Customer cancelled");
        server.verify();
    }
}
