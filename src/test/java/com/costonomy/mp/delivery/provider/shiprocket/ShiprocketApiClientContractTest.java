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
