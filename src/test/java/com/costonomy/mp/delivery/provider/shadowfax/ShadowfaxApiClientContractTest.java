package com.costonomy.mp.delivery.provider.shadowfax;

import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class ShadowfaxApiClientContractTest {

    private ShadowfaxProperties properties;
    private MockRestServiceServer server;
    private ShadowfaxApiClient client;

    private static final String BASE_URL = "https://api-star.shadowfax.in";

    @BeforeEach
    void setUp() {
        properties = new ShadowfaxProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setAuthToken("test-sfx-token");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new ShadowfaxApiClient(properties, builder, new ObjectMapper());
        server = customizer.getServer();
    }

    private DeliveryProvider.QuoteRequest quoteRequest() {
        return new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar, Bengaluru, 560038",
                "Koramangala, Bengaluru, 560034");
    }

    private DeliveryProvider.BookingRequest bookingRequest() {
        return new DeliveryProvider.BookingRequest(
                101L, "sfx_q_dummy",
                "Indiranagar, Bengaluru, 560038",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", "+919876500000",
                "Koramangala, Bengaluru, 560034",
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-sfx-10-1");
    }


    private static final String SERVICEABILITY_URL =
            BASE_URL + "/v1/clients/serviceability/?service=Regular&pincodes=560038,560034";

    private DeliveryProvider.BookingRequest hydBooking(Long supplierOrderId, BigDecimal weightKg, BigDecimal goodsValue,
                                                       DeliveryProvider.Locality drop, String dropPhone) {
        return new DeliveryProvider.BookingRequest(
                supplierOrderId, "sfx_q_dummy",
                "Annapurna Stores, Secunderabad, Telangana, 500003",
                new BigDecimal("17.4399"), new BigDecimal("78.4983"),
                "Store Desk", "+919876500000",
                "Gachibowli, Hyderabad, Telangana, 500081",
                new BigDecimal("17.4401"), new BigDecimal("78.3489"),
                "Customer Asha", dropPhone,
                "mp-delivery-10-1", weightKg, BigDecimal.valueOf(0.02), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Secunderabad", "Telangana", "500003"),
                drop, goodsValue);
    }

    private DeliveryProvider.BookingRequest validHydBooking() {
        return hydBooking(101L, new BigDecimal("12.5"), new BigDecimal("1834.50"),
                new DeliveryProvider.Locality("Hyderabad", "Telangana", "500081"), "+919876511111");
    }

    @Test
    @DisplayName("declines a serviceable route because Shadowfax gives no fare (D-121)")
    void declinesServiceableRouteBecauseNoCarrierFare() {
        server.expect(requestTo(SERVICEABILITY_URL))
                .andRespond(withSuccess("""
                        [
                          {"code": 560038, "services": ["Regular"]},
                          {"code": 560034, "services": ["Regular"]}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.amount()).isNull();
        assertThat(quote.etaMinutes()).isNull();
        assertThat(quote.providerQuoteId()).isNull();
        assertThat(quote.declineReason()).contains("no fare");
        server.verify(); // the serviceability check really ran; the decline is not a shortcut
    }

    @Test
    @DisplayName("declines when a pincode is not listed by Shadowfax")
    void declinesWhenPincodeNotListed() {
        server.expect(requestTo(SERVICEABILITY_URL))
                .andRespond(withSuccess("[{\"code\": 560038, \"services\": [\"Regular\"]}]", MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("does not serve pincodes 560038 to 560034");
    }

    @Test
    @DisplayName("declines when the Regular service is not offered")
    void declinesWhenServicesLackRegular() {
        server.expect(requestTo(SERVICEABILITY_URL))
                .andRespond(withSuccess("""
                        [{"code": 560038, "services": ["Regular"]}, {"code": 560034, "services": ["Express"]}]
                        """, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("does not serve pincodes");
    }

    @Test
    @DisplayName("fails closed, non-retryably, when the serviceability body is not an array")
    void failsClosedOnUnparseableServiceability() {
        server.expect(requestTo(SERVICEABILITY_URL))
                .andRespond(withSuccess("{\"message\": \"ok\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(ShadowfaxContractException.class)
                .satisfies(e -> assertThat(((DeliveryProviderException) e).retryable()).isFalse());
    }

    @Test
    @DisplayName("fails closed when a listed pincode has no services array")
    void failsClosedOnEntryWithoutServices() {
        server.expect(requestTo(SERVICEABILITY_URL))
                .andRespond(withSuccess("[{\"code\": 560038}, {\"code\": 560034, \"services\": [\"Regular\"]}]",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(ShadowfaxContractException.class);
    }

    @Test
    @DisplayName("a serviceability 5xx is a retryable failure, never a serviceable quote")
    void failsRetryablyOnServiceability5xx() {
        server.expect(requestTo(SERVICEABILITY_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .satisfies(e -> assertThat(((DeliveryProviderException) e).retryable()).isTrue());
    }

    @Test
    @DisplayName("a serviceability 401 is a non-retryable failure, never a serviceable quote")
    void failsNonRetryablyOnServiceability4xx() {
        server.expect(requestTo(SERVICEABILITY_URL)).andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .satisfies(e -> assertThat(((DeliveryProviderException) e).retryable()).isFalse());
    }

    @Test
    @DisplayName("a serviceability timeout is a retryable failure, never a serviceable quote")
    void failsRetryablyOnServiceabilityTimeout() {
        server.expect(requestTo(SERVICEABILITY_URL))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("read timed out"); });

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .satisfies(e -> assertThat(((DeliveryProviderException) e).retryable()).isTrue());
    }

    @Test
    @DisplayName("declines without calling Shadowfax when an address has no pincode")
    void declinesWithoutHttpWhenAddressHasNoPincode() {
        var request = new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar, Bengaluru", "Koramangala, Bengaluru");

        var quote = client.calculateQuote(request);

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("pincode");
        server.verify(); // no expectations were registered, so any HTTP call would have failed the test
    }

    @Test
    @DisplayName("declines quote when auth token is missing")
    void declinesWhenNotConfigured() {
        properties.setAuthToken("");
        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("credentials not configured");
    }

    @Test
    @DisplayName("refuses to book without a carrier fare and sends nothing (D-121)")
    void refusesToBookWithoutFareAndSendsNothing() {
        assertThatThrownBy(() -> client.createOrder(validHydBooking()))
                .isInstanceOf(ShadowfaxContractException.class)
                .hasMessageContaining("fare")
                .satisfies(e -> assertThat(((DeliveryProviderException) e).retryable()).isFalse());
        server.verify(); // no expectation registered: a POST would have failed the test instead
    }

    @Test
    @DisplayName("order payload uses our real locality, weight and value, and none of the old hardcodes")
    @SuppressWarnings("unchecked")
    void orderPayloadUsesRealLocalityWeightAndValue() {
        var payload = client.orderPayload(validHydBooking());

        var customer = (java.util.Map<String, Object>) payload.get("customer_details");
        var pickup = (java.util.Map<String, Object>) payload.get("pickup_details");
        var order = (java.util.Map<String, Object>) payload.get("order_details");

        assertThat(customer.get("city")).isEqualTo("Hyderabad");
        assertThat(customer.get("state")).isEqualTo("Telangana");
        assertThat(customer.get("pincode")).isEqualTo(500081);
        assertThat(pickup.get("pincode")).isEqualTo(500003);
        assertThat(order.get("actual_weight")).isEqualTo(12500);
        assertThat(order).doesNotContainKey("volumetric_weight");
        assertThat((BigDecimal) order.get("product_value")).isEqualByComparingTo("1834.50");
        assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo("1834.50");
        assertThat(customer.get("contact")).isEqualTo("9876511111");

        String all = payload.toString();
        assertThat(all).doesNotContain("Bengaluru", "Karnataka", "9876543210", "560038", "560034", "MANDI-101");
    }

    @Test
    @DisplayName("order payload rejects missing or invalid booking data by naming the field")
    void orderPayloadRejectsMissingData() {
        var hydDrop = new DeliveryProvider.Locality("Hyderabad", "Telangana", "500081");
        var cases = java.util.Map.<String, DeliveryProvider.BookingRequest>of(
                "drop locality", hydBooking(101L, new BigDecimal("12.5"), new BigDecimal("100"), null, "+919876511111"),
                "drop city", hydBooking(101L, new BigDecimal("12.5"), new BigDecimal("100"),
                        new DeliveryProvider.Locality(" ", "Telangana", "500081"), "+919876511111"),
                "drop pincode", hydBooking(101L, new BigDecimal("12.5"), new BigDecimal("100"),
                        new DeliveryProvider.Locality("Hyderabad", "Telangana", "5000"), "+919876511111"),
                "weightKg", hydBooking(101L, null, new BigDecimal("100"), hydDrop, "+919876511111"),
                "goodsValue", hydBooking(101L, new BigDecimal("12.5"), BigDecimal.ZERO, hydDrop, "+919876511111"),
                "drop contact phone", hydBooking(101L, new BigDecimal("12.5"), new BigDecimal("100"), hydDrop, null),
                "drop contact phone ", hydBooking(101L, new BigDecimal("12.5"), new BigDecimal("100"), hydDrop, "12345"),
                "supplierOrderId", hydBooking(null, new BigDecimal("12.5"), new BigDecimal("100"), hydDrop, "+919876511111"));

        cases.forEach((field, request) -> assertThatThrownBy(() -> client.orderPayload(request))
                .as(field)
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining(field.trim())
                .satisfies(e -> assertThat(((DeliveryProviderException) e).retryable()).isFalse()));
    }

    @Test
    @DisplayName("parses track response and returns newest-first events from tracking_details")
    void parsesTrackResponse() {
        String responseBody = """
                {
                  "message": "Success",
                  "order_details": {
                    "id": 145475249,
                    "awb_number": "SF222344412TST",
                    "status": "delivered",
                    "delivery_details": {
                      "name": "Rider Deepak",
                      "contact": "9876543210"
                    },
                    "tracking_details": [
                      {
                        "created": "2026-10-02T10:00:00Z",
                        "status_id": "new",
                        "status": "New",
                        "remarks": "Order placed"
                      },
                      {
                        "created": "2026-10-02T10:15:00Z",
                        "status_id": "assigned_for_delivery",
                        "status": "Assigned For Delivery",
                        "remarks": "Rider assigned"
                      },
                      {
                        "created": "2026-10-02T10:45:00Z",
                        "status_id": "delivered",
                        "status": "Delivered",
                        "remarks": "Delivered to customer"
                      }
                    ]
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v4/clients/orders/SF222344412TST/track/"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("SF222344412TST");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.driverName()).isEqualTo("Rider Deepak");
        assertThat(delivery.driverPhone()).isEqualTo("9876543210");
        assertThat(delivery.events()).hasSize(3);

        // Newest first order
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(delivery.events().get(2).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("cancels order by AWB request_id")
    void cancelsOrderByAwb() {
        server.expect(requestTo(BASE_URL + "/v3/clients/orders/cancel/"))
                .andRespond(withSuccess("{\"responseMsg\": \"Cancelled\", \"responseCode\": 200}", MediaType.APPLICATION_JSON));

        client.cancel("SF222344412TST", "Cancelled by customer");
        server.verify();
    }

    @Test
    @DisplayName("declines quote when route exceeds 30 km intra-city radius limit")
    void declinesWhenExceeds30KmRadius() {
        // Indiranagar, Bengaluru to Hosur (~45 km)
        var longRouteRequest = new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.7409"), new BigDecimal("77.8253"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar, Bengaluru, 560038",
                "Hosur, 635109");

        var quote = client.calculateQuote(longRouteRequest);

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("Exceeds 30 km intra-city radius limit");
    }
}
