package com.costonomy.mp.delivery.provider.shadowfax;

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
        properties.setBaseFee(new BigDecimal("60.00"));
        properties.setPerKmFee(new BigDecimal("12.00"));

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

    @Test
    @DisplayName("calculates quote with coordinates and rate card")
    void calculatesQuoteWithCoordinates() {
        String serviceabilityResponse = """
                [
                  {"code": 560038, "services": ["Regular"]},
                  {"code": 560034, "services": ["Regular"]}
                ]
                """;

        server.expect(requestTo(BASE_URL + "/v1/clients/serviceability/?service=Regular&pincodes=560038,560034"))
                .andRespond(withSuccess(serviceabilityResponse, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.etaMinutes()).isNotNull().isGreaterThan(0);
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
    @DisplayName("creates order and parses awb_number from Shadowfax response")
    void createsOrderSuccessfully() {
        String responseBody = """
                {
                  "message": "Success",
                  "errors": null,
                  "data": {
                    "id": 2035397,
                    "client_name": "Test Client",
                    "client_order_id": "mp-sfx-10-1",
                    "awb_number": "SF36089989TMA",
                    "product_value": 500.0,
                    "promised_delivery_date": "2026-10-02T18:30:00Z",
                    "status": "new"
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v3/clients/orders/"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var booking = client.createOrder(bookingRequest());

        assertThat(booking.providerDeliveryId()).isEqualTo("SF36089989TMA");
        assertThat(booking.trackingUrl()).contains("SF36089989TMA");
        assertThat(booking.amount()).isNotNull();
    }

    @Test
    @DisplayName("fails loud when awb_number is missing in create-order response")
    void failsLoudOnMissingAwb() {
        String responseBody = """
                {
                  "message": "Success",
                  "data": { "client_order_id": "mp-sfx-10-1" }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v3/clients/orders/"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createOrder(bookingRequest()))
                .isInstanceOf(ShadowfaxContractException.class)
                .hasMessageContaining("awb_number");
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
