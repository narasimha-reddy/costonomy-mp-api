package com.costonomy.mp.delivery.provider.porter;

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


    private DeliveryProvider.BookingRequest hydBooking(String dropPhone, DeliveryProvider.Locality drop) {
        return new DeliveryProvider.BookingRequest(
                101L, "prtr_q_dummy",
                "Annapurna Stores, Secunderabad, Telangana, 500003",
                new BigDecimal("17.4399"), new BigDecimal("78.4983"),
                "Store Desk", "+919876500000",
                "Gachibowli, Hyderabad, Telangana, 500081",
                new BigDecimal("17.4401"), new BigDecimal("78.3489"),
                "Customer Asha", dropPhone,
                "mp-delivery-10-1",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Secunderabad", "Telangana", "500003"),
                drop, new BigDecimal("1834.50"));
    }

    @Test
    @DisplayName("declines because Porter's fare contract is unverified and makes no HTTP call (D-102)")
    void declinesBecauseFareContractUnverified() {
        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.amount()).isNull();
        assertThat(quote.etaMinutes()).isNull();
        assertThat(quote.providerQuoteId()).isNull();
        assertThat(quote.declineReason()).contains("fare");
        server.verify(); // no expectations registered: any HTTP call would have failed the test
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
    @DisplayName("refuses to book without a verified carrier fare and sends nothing (D-102)")
    void refusesToBookWithoutFareAndSendsNothing() {
        var hydDrop = new DeliveryProvider.Locality("Hyderabad", "Telangana", "500081");

        assertThatThrownBy(() -> client.createOrder(hydBooking("+919876511111", hydDrop)))
                .isInstanceOf(PorterContractException.class)
                .hasMessageContaining("fare")
                .satisfies(e -> assertThat(((DeliveryProviderException) e).retryable()).isFalse());
        server.verify(); // no expectation registered: a POST would have failed the test instead
    }

    @Test
    @DisplayName("order payload takes city and phones from our records, with no hardcoded values")
    @SuppressWarnings("unchecked")
    void orderPayloadUsesLocalityCityAndRealPhones() {
        var payload = client.orderPayload(hydBooking("9876511111",
                new DeliveryProvider.Locality("Hyderabad", "Telangana", "500081")));

        var drop = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) payload.get("drop_details")).get("address");
        var pickup = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) payload.get("pickup_details")).get("address");
        var dropContact = (java.util.Map<String, Object>) drop.get("contact_details");

        assertThat(drop.get("city")).isEqualTo("Hyderabad");
        assertThat(pickup.get("city")).isEqualTo("Secunderabad");
        assertThat(dropContact.get("phone_number")).isEqualTo("+919876511111");
        assertThat(((java.util.Map<String, Object>) payload.get("customer")).get("name")).isEqualTo("Costonomy");
        assertThat(payload.toString()).doesNotContain("Bengaluru", "9876543210", "Mandi");
    }

    @Test
    @DisplayName("order payload rejects a missing or invalid phone or city by naming the field")
    void orderPayloadRejectsMissingPhoneOrCity() {
        var hydDrop = new DeliveryProvider.Locality("Hyderabad", "Telangana", "500081");

        assertThatThrownBy(() -> client.orderPayload(hydBooking(null, hydDrop)))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("drop contact phone");
        assertThatThrownBy(() -> client.orderPayload(hydBooking("12345", hydDrop)))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("drop contact phone");
        assertThatThrownBy(() -> client.orderPayload(hydBooking("+919876511111",
                new DeliveryProvider.Locality(" ", "Telangana", "500081"))))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("drop city");
        assertThatThrownBy(() -> client.orderPayload(hydBooking("+919876511111", null)))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("drop city");
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
    @DisplayName("declines quote when route exceeds 30 km intra-city radius limit")
    void declinesWhenExceeds30KmRadius() {
        // Indiranagar, Bengaluru to Hosur (~45 km)
        var longRouteRequest = new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.7409"), new BigDecimal("77.8253"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar, Bengaluru",
                "Hosur");

        var quote = client.calculateQuote(longRouteRequest);

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("Exceeds 30 km intra-city radius limit");
    }
}
