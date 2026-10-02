package com.costonomy.mp.delivery.provider.xpressbees;

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

class XpressbeesApiClientContractTest {

    private XpressbeesProperties properties;
    private MockRestServiceServer server;
    private XpressbeesApiClient client;

    private static final String BASE_URL = "https://shipment.xpressbees.com/api";
    private static final String TOKEN = "test-xpressbees-token";

    @BeforeEach
    void setUp() {
        properties = new XpressbeesProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setToken(TOKEN);
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new XpressbeesApiClient(properties, builder, new ObjectMapper());
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
                101L, "xb_q_101_dummy",
                "Indiranagar, Bengaluru, 560038",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", "+919876500000",
                "Koramangala, Bengaluru, 560034",
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-xb-101",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("calculates quote returning real carrier fare from Xpressbees serviceability")
    void calculateQuote_success() {
        String serviceabilityJson = """
                {
                  "status": true,
                  "data": {
                    "rate": 68.00,
                    "estimated_delivery_days": 1
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/courier/serviceability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.origin").value("560038"))
                .andExpect(jsonPath("$.destination").value("560034"))
                .andRespond(withSuccess(serviceabilityJson, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("68.00");
        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.distanceKm()).isNotNull();
        assertThat(quote.providerQuoteId()).startsWith("xb_q_");
        server.verify();
    }

    @Test
    @DisplayName("declines quote when route exceeds 30 km intra-city radius limit (D-101)")
    void calculateQuote_exceeds30Km() {
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
        server.verify();
    }

    @Test
    @DisplayName("declines quote when address has no 6-digit pincode")
    void calculateQuote_missingPincode() {
        var noPincodeRequest = new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar without pin",
                "Koramangala without pin");

        var quote = client.calculateQuote(noPincodeRequest);

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("needs a pickup and drop pincode");
        server.verify();
    }

    @Test
    @DisplayName("throws XpressbeesContractException when serviceability response is missing rate/fare")
    void calculateQuote_missingFareThrowsContractException() {
        String noFareJson = """
                {
                  "status": true,
                  "data": {
                    "is_serviceable": true
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/courier/serviceability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(noFareJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(XpressbeesContractException.class)
                .hasMessageContaining("missing fare");
        server.verify();
    }

    @Test
    @DisplayName("creates order with Xpressbees and returns Booking")
    void createOrder_success() {
        String createResponseJson = """
                {
                  "status": true,
                  "data": {
                    "awb_number": "XB-AWB-887766",
                    "rate": 68.00
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/shipments/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.order_number").value("mp-xb-101"))
                .andExpect(jsonPath("$.pickup_details.pincode").value("560038"))
                .andExpect(jsonPath("$.delivery_details.pincode").value("560034"))
                .andExpect(jsonPath("$.delivery_details.phone").value("+919876511111"))
                .andRespond(withSuccess(createResponseJson, MediaType.APPLICATION_JSON));

        var booking = client.createOrder(bookingRequest());

        assertThat(booking.providerDeliveryId()).isEqualTo("XB-AWB-887766");
        assertThat(booking.amount()).isEqualByComparingTo("68.00");
        assertThat(booking.trackingUrl()).contains("XB-AWB-887766");
        server.verify();
    }

    @Test
    @DisplayName("polls tracking status and maps current status and history")
    void getStatus_success() {
        String trackingJson = """
                {
                  "status": true,
                  "data": {
                    "status": "in_transit",
                    "history": [
                      {
                        "status": "manifested",
                        "message": "Shipment Manifested",
                        "time": "2026-10-02T16:00:00Z"
                      },
                      {
                        "status": "picked_up",
                        "message": "Shipment Picked Up",
                        "time": "2026-10-02T17:00:00Z"
                      },
                      {
                        "status": "in_transit",
                        "message": "In Transit to Destination",
                        "time": "2026-10-02T18:00:00Z"
                      }
                    ]
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/shipments/track/XB-AWB-887766"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(trackingJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("XB-AWB-887766");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(delivery.events()).hasSize(3);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        assertThat(delivery.events().get(2).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PENDING);
        server.verify();
    }

    @Test
    @DisplayName("synthesizes PICKED_UP event before DELIVERED when history array is empty")
    void getStatus_delivered_synthesizesEvents() {
        String deliveredJson = """
                {
                  "status": true,
                  "data": {
                    "status": "delivered"
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/shipments/track/XB-AWB-887766"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(deliveredJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("XB-AWB-887766");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events()).hasSize(2);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        server.verify();
    }

    @Test
    @DisplayName("cancels order with Xpressbees")
    void cancel_success() {
        server.expect(requestTo(BASE_URL + "/v1/shipments/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.awb_number").value("XB-AWB-887766"))
                .andExpect(jsonPath("$.reason").value("Store cancelled"))
                .andRespond(withSuccess("{\"status\":true}", MediaType.APPLICATION_JSON));

        client.cancel("XB-AWB-887766", "Store cancelled");
        server.verify();
    }

    @Test
    @DisplayName("throws retryable DeliveryProviderException on server 500 error")
    void serverError_throwsRetryableException() {
        server.expect(requestTo(BASE_URL + "/v1/courier/serviceability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .satisfies(ex -> assertThat(((DeliveryProviderException) ex).retryable()).isTrue());
        server.verify();
    }

    @Test
    @DisplayName("throttles calls when rate limit RPS is exhausted")
    void throttlesWhenRateLimitExceeded() {
        properties.setRateLimitRps(0);
        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        var rateLimitedClient = new XpressbeesApiClient(properties, builder, new ObjectMapper());

        assertThatThrownBy(() -> rateLimitedClient.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("rate limit exceeded")
                .satisfies(ex -> assertThat(((DeliveryProviderException) ex).retryable()).isTrue());
    }
}
