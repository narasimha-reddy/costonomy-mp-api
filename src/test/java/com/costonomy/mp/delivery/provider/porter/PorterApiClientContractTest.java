package com.costonomy.mp.delivery.provider.porter;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PorterApiClientContractTest {

    private PorterProperties properties;
    private MockRestServiceServer server;
    private PorterApiClient client;

    private static final String BASE_URL = "https://api.porter.in";

    @BeforeEach
    void setUp() {
        properties = new PorterProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey("test-porter-key");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);
        properties.setBaseFee(new BigDecimal("50.00"));
        properties.setPerKmFee(new BigDecimal("14.00"));

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new PorterApiClient(properties, builder, new ObjectMapper());
        server = customizer.getServer();
    }

    private DeliveryProvider.QuoteRequest quoteRequest() {
        return new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar, Bengaluru",
                "Koramangala, Bengaluru");
    }

    private DeliveryProvider.BookingRequest bookingRequest() {
        return new DeliveryProvider.BookingRequest(
                101L, "prtr_q_dummy",
                "Indiranagar, Bengaluru",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", "+919876500000",
                "Koramangala, Bengaluru",
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-prtr-10-1",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER);
    }

    @Test
    @DisplayName("calculates quote with coordinates and rate card from Porter cost API")
    void calculatesQuoteWithCoordinates() {
        String costResponse = """
                {
                  "cost": {
                    "amount": 95.00,
                    "currency": "INR"
                  },
                  "eta": 20,
                  "distance": 5.4,
                  "quote_id": "prtr_q_12345"
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/cost"))
                .andRespond(withSuccess(costResponse, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("95.00");
        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.etaMinutes()).isEqualTo(20);
        assertThat(quote.distanceKm()).isEqualTo(5.4);
    }

    @Test
    @DisplayName("declines quote when API key is missing")
    void declinesWhenNotConfigured() {
        properties.setApiKey("");
        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("credentials not configured");
    }

    @Test
    @DisplayName("creates order and parses order_id from Porter response")
    void createsOrderAndParsesOrderId() {
        String createResponse = """
                {
                  "order_id": "CR10029384",
                  "status": "allocating",
                  "tracking_url": "https://track.porter.in/CR10029384"
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/create"))
                .andRespond(withSuccess(createResponse, MediaType.APPLICATION_JSON));

        var booking = client.createOrder(bookingRequest());

        assertThat(booking.providerDeliveryId()).isEqualTo("CR10029384");
        assertThat(booking.currency()).isEqualTo("INR");
        assertThat(booking.amount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(booking.trackingUrl()).isEqualTo("https://track.porter.in/CR10029384");
    }

    @Test
    @DisplayName("tracks status, driver details and timeline events")
    void tracksStatusAndDriverDetails() {
        String trackResponse = """
                {
                  "order_id": "CR10029384",
                  "status": "assigned",
                  "partner_details": {
                    "name": "Ramesh Kumar",
                    "mobile": "+919988776655",
                    "vehicle_number": "KA01AB1234"
                  },
                  "events": [
                    {
                      "status": "created",
                      "description": "Order created",
                      "timestamp": "2026-10-02T10:00:00Z"
                    },
                    {
                      "status": "assigned",
                      "description": "Driver assigned",
                      "timestamp": "2026-10-02T10:05:00Z"
                    }
                  ]
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/CR10029384"))
                .andRespond(withSuccess(trackResponse, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("CR10029384");

        assertThat(delivery.providerDeliveryId()).isEqualTo("CR10029384");
        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(delivery.driverName()).isEqualTo("Ramesh Kumar");
        assertThat(delivery.driverPhone()).isEqualTo("+919988776655");
        assertThat(delivery.driverVehicle()).isEqualTo("KA01AB1234");
        assertThat(delivery.events()).hasSize(2);
        // Events are newest first
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("synthesizes PICKED_UP preceding DELIVERED when events array is absent")
    void synthesizesEventsOnDelivered() {
        String trackResponse = """
                {
                  "order_id": "CR10029384",
                  "status": "delivered"
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/CR10029384"))
                .andRespond(withSuccess(trackResponse, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("CR10029384");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events()).hasSize(2);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
    }

    @Test
    @DisplayName("cancels order successfully")
    void cancelsOrder() {
        String cancelResponse = """
                {
                  "order_id": "CR10029384",
                  "status": "cancelled"
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/CR10029384/cancel"))
                .andRespond(withSuccess(cancelResponse, MediaType.APPLICATION_JSON));

        client.cancel("CR10029384", "Client requested cancel");
        server.verify();
    }

    @Test
    @DisplayName("throws PorterContractException when order creation response misses order_id")
    void throwsContractExceptionOnMissingOrderId() {
        String invalidResponse = """
                {
                  "message": "Invalid request"
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/orders/create"))
                .andRespond(withSuccess(invalidResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createOrder(bookingRequest()))
                .isInstanceOf(PorterContractException.class)
                .hasMessageContaining("missing order_id");
    }
}
