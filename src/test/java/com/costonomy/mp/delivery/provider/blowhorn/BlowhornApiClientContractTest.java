package com.costonomy.mp.delivery.provider.blowhorn;

import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlowhornApiClientContractTest {

    private BlowhornProperties properties;
    private MockRestServiceServer server;
    private BlowhornApiClient client;

    private static final String BASE_URL = "https://beta.blowhorn.com/api";
    private static final String API_KEY = "test-blowhorn-api-key";

    @BeforeEach
    void setUp() {
        properties = new BlowhornProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey(API_KEY);
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new BlowhornApiClient(properties, builder, new ObjectMapper());
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
                101L, "bh_q_101_dummy",
                "Indiranagar, Bengaluru, 560038",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", "+919876500000",
                "Koramangala, Bengaluru, 560034",
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-bh-101",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("polls tracking status and maps current status and events")
    void getStatus_success() {
        String trackingJson = """
                {
                  "order_id": "BH-AWB-9999",
                  "status": "in_transit",
                  "driver_details": {
                    "name": "Sunil Pilot",
                    "phone": "+919876543210",
                    "vehicle_number": "KA-04-BH-1122"
                  },
                  "events": [
                    {
                      "status": "assigned",
                      "description": "Pilot assigned",
                      "timestamp": "1727870000000"
                    },
                    {
                      "status": "picked_up",
                      "description": "Shipment picked up",
                      "timestamp": "1727871000000"
                    },
                    {
                      "status": "in_transit",
                      "description": "In transit to destination",
                      "timestamp": "1727872000000"
                    }
                  ]
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/BH-AWB-9999/track"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("API_KEY", API_KEY))
                .andRespond(withSuccess(trackingJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("BH-AWB-9999");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(delivery.driverName()).isEqualTo("Sunil Pilot");
        assertThat(delivery.driverPhone()).isEqualTo("+919876543210");
        assertThat(delivery.driverVehicle()).isEqualTo("KA-04-BH-1122");
        assertThat(delivery.events()).hasSize(3);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        assertThat(delivery.events().get(2).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
        server.verify();
    }

    @Test
    @DisplayName("synthesizes PICKED_UP event before DELIVERED when events array is empty")
    void getStatus_delivered_synthesizesEvents() {
        String deliveredJson = """
                {
                  "order_id": "BH-AWB-9999",
                  "status": "delivered"
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/BH-AWB-9999/track"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("API_KEY", API_KEY))
                .andRespond(withSuccess(deliveredJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("BH-AWB-9999");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events()).hasSize(2);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        server.verify();
    }

    @Test
    @DisplayName("polls location successfully when current_location is present")
    void location_success() {
        String locationJson = """
                {
                  "order_id": "BH-AWB-9999",
                  "status": "in_transit",
                  "current_location": {
                    "latitude": 12.9550,
                    "longitude": 77.6150,
                    "bearing": 180.0,
                    "speed": 32.5
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/BH-AWB-9999/track"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("API_KEY", API_KEY))
                .andRespond(withSuccess(locationJson, MediaType.APPLICATION_JSON));

        var location = client.location("BH-AWB-9999");

        assertThat(location).isNotNull();
        assertThat(location.latitude()).isEqualByComparingTo("12.9550");
        assertThat(location.longitude()).isEqualByComparingTo("77.6150");
        assertThat(location.bearing()).isEqualTo(180.0);
        assertThat(location.speedKmph()).isEqualTo(32.5);
        server.verify();
    }

    @Test
    @DisplayName("cancels order with Blowhorn")
    void cancel_success() {
        server.expect(requestTo(BASE_URL + "/v1/orders/BH-AWB-9999/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("API_KEY", API_KEY))
                .andExpect(jsonPath("$.cancellation_reason").value("Store request"))
                .andRespond(withSuccess("{\"status\":\"CANCELLED\"}", MediaType.APPLICATION_JSON));

        client.cancel("BH-AWB-9999", "Store request");
        server.verify();
    }

    @Test
    @DisplayName("throttles calls when rate limit RPS is exhausted")
    void throttlesWhenRateLimitExceeded() {
        properties.setRateLimitRps(0);
        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        var rateLimitedClient = new BlowhornApiClient(properties, builder, new ObjectMapper());

        assertThatThrownBy(() -> rateLimitedClient.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("rate limit exceeded")
                .satisfies(ex -> assertThat(((DeliveryProviderException) ex).retryable()).isTrue());
    }

    @Test
    @DisplayName("quote is declined without any carrier call: the fare contract is unverified (D-121)")
    void calculateQuote_failsClosedWithoutHttp() {
        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.amount()).isNull();
        assertThat(quote.declineReason()).contains("not verified");
        server.verify();
    }

    @Test
    @DisplayName("booking is refused before any carrier call: no invented fare, no orphan consignment (D-121)")
    void createOrder_refusedWithoutHttp() {
        assertThatThrownBy(() -> client.createOrder(bookingRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("not verified");
        server.verify();
    }
}
