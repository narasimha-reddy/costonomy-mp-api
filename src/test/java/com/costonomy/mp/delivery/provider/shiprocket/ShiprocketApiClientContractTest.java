package com.costonomy.mp.delivery.provider.shiprocket;

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

class ShiprocketApiClientContractTest {

    private ShiprocketProperties properties;
    private MockRestServiceServer server;
    private ShiprocketApiClient client;

    private static final String BASE_URL = "https://apiv2.shiprocket.in";

    @BeforeEach
    void setUp() {
        properties = new ShiprocketProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiToken("test-shiprocket-token");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new ShiprocketApiClient(properties, builder, new ObjectMapper());
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
                101L, "sr_q_101_dummy",
                "Indiranagar, Bengaluru, 560038",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", "+919876500000",
                "Koramangala, Bengaluru, 560034",
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-sr-10-1",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("calculates quote selecting lowest available courier rate from Shiprocket serviceability")
    void calculateQuote_success() {
        String serviceabilityJson = """
                {
                  "status": 200,
                  "data": {
                    "available_courier_companies": [
                      {
                        "courier_company_id": 101,
                        "courier_name": "Delhivery Local",
                        "rate": 75.50,
                        "estimated_delivery_days": 1
                      },
                      {
                        "courier_company_id": 102,
                        "courier_name": "Shadowfax Quick",
                        "rate": 90.00,
                        "estimated_delivery_days": 1
                      }
                    ]
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/external/courier/serviceability/?pickup_postcode=560038&delivery_postcode=560034&weight=2.5000&cod=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-shiprocket-token"))
                .andRespond(withSuccess(serviceabilityJson, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("75.50");
        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.distanceKm()).isNotNull();
        assertThat(quote.providerQuoteId()).startsWith("sr_q_101_");
        server.verify();
    }

    @Test
    @DisplayName("declines quote when route exceeds 30 km intra-city radius limit (D-101)")
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
        server.verify(); // No HTTP call
    }

    @Test
    @DisplayName("declines quote when no couriers are serviceable for route")
    void declinesWhenNoCouriersAvailable() {
        String emptyServiceabilityJson = """
                {
                  "status": 200,
                  "data": {
                    "available_courier_companies": []
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/external/courier/serviceability/?pickup_postcode=560038&delivery_postcode=560034&weight=2.5000&cod=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyServiceabilityJson, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("No Shiprocket courier serviceable");
        server.verify();
    }

    @Test
    @DisplayName("declines quote when address has no 6-digit pincode")
    void declinesWhenPincodeMissing() {
        var noPincodeRequest = new DeliveryProvider.QuoteRequest(
                101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar without pincode",
                "Koramangala without pincode");

        var quote = client.calculateQuote(noPincodeRequest);

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("needs a pickup and drop pincode");
        server.verify();
    }

    @Test
    @DisplayName("creates adhoc order with Shiprocket and returns Booking")
    void createOrder_success() {
        String createResponseJson = """
                {
                  "order_id": 123456,
                  "shipment_id": 987654,
                  "status": "NEW",
                  "total_amount": 75.50
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/external/orders/create/adhoc"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-shiprocket-token"))
                .andExpect(jsonPath("$.order_id").value("mp-sr-10-1"))
                .andExpect(jsonPath("$.billing_pincode").value("560034"))
                .andExpect(jsonPath("$.billing_phone").value("+919876511111"))
                .andRespond(withSuccess(createResponseJson, MediaType.APPLICATION_JSON));

        var booking = client.createOrder(bookingRequest());

        assertThat(booking.providerDeliveryId()).isEqualTo("987654");
        assertThat(booking.amount()).isEqualByComparingTo("75.50");
        assertThat(booking.trackingUrl()).contains("987654");
        server.verify();
    }

    @Test
    @DisplayName("polls tracking status and maps current_status with historic events")
    void getStatus_success() {
        String trackingJson = """
                {
                  "tracking_data": {
                    "track_status": 1,
                    "shipment_track": [
                      {
                        "current_status": "OUT FOR DELIVERY",
                        "activity": "Out for delivery",
                        "date": "2026-10-02 18:30:00"
                      }
                    ],
                    "shipment_track_activities": [
                      {
                        "activity": "Manifest Generated",
                        "date": "2026-10-02 17:00:00"
                      },
                      {
                        "activity": "Out for delivery",
                        "date": "2026-10-02 18:30:00"
                      }
                    ]
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/external/courier/track/shipment/987654"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(trackingJson, MediaType.APPLICATION_JSON));

        var status = client.getStatus("987654");

        assertThat(status.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(status.events()).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("synthesizes PICKED_UP event before DELIVERED when activities array is absent")
    void getStatus_delivered_synthesizesPickedUp() {
        String deliveredJson = """
                {
                  "tracking_data": {
                    "shipment_track": [
                      {
                        "current_status": "DELIVERED"
                      }
                    ]
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/v1/external/courier/track/shipment/987654"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(deliveredJson, MediaType.APPLICATION_JSON));

        var status = client.getStatus("987654");

        assertThat(status.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(status.events()).hasSize(2);
        assertThat(status.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(status.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        server.verify();
    }

    @Test
    @DisplayName("cancels order with Shiprocket")
    void cancel_success() {
        server.expect(requestTo(BASE_URL + "/v1/external/orders/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ids[0]").value("987654"))
                .andRespond(withSuccess("{\"status\": 200}", MediaType.APPLICATION_JSON));

        client.cancel("987654", "Customer cancelled");
        server.verify();
    }

    @Test
    @DisplayName("throws retryable DeliveryProviderException on server 500 error")
    void throwsRetryableExceptionOnServerError() {
        server.expect(requestTo(BASE_URL + "/v1/external/courier/serviceability/?pickup_postcode=560038&delivery_postcode=560034&weight=2.5000&cod=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .satisfies(ex -> assertThat(((DeliveryProviderException) ex).retryable()).isTrue());
        server.verify();
    }
}
