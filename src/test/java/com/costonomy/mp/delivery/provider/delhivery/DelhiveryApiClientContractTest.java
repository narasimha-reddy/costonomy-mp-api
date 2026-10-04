package com.costonomy.mp.delivery.provider.delhivery;

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

class DelhiveryApiClientContractTest {

    private DelhiveryProperties properties;
    private MockRestServiceServer server;
    private DelhiveryApiClient client;

    private static final String BASE_URL = "https://track.delhivery.com";
    private static final String API_TOKEN = "test-delhivery-token";

    @BeforeEach
    void setUp() {
        properties = new DelhiveryProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiToken(API_TOKEN);
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new DelhiveryApiClient(properties, builder, new ObjectMapper());
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
                101L, "dlv_q_101_dummy",
                "Indiranagar, Bengaluru, 560038",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", "+919876500000",
                "Koramangala, Bengaluru, 560034",
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-dlv-101",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("polls tracking status and maps current status and scan activities")
    void getStatus_success() {
        String trackingJson = """
                {
                  "ShipmentData": [
                    {
                      "Shipment": {
                        "Status": {
                          "Status": "Out for Delivery"
                        },
                        "Scans": [
                          {
                            "ScanDetail": {
                              "ScanType": "Manifested",
                              "Instructions": "SoftData Received",
                              "ScanDateTime": "2026-10-02T16:00:00Z"
                            }
                          },
                          {
                            "ScanDetail": {
                              "ScanType": "In Transit",
                              "Instructions": "In Transit to Destination Hub",
                              "ScanDateTime": "2026-10-02T17:00:00Z"
                            }
                          },
                          {
                            "ScanDetail": {
                              "ScanType": "Out for Delivery",
                              "Instructions": "Out for delivery with rider",
                              "ScanDateTime": "2026-10-02T18:00:00Z"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        server.expect(requestTo(BASE_URL + "/api/v1/packages/json/?waybill=DEL-WB-998877"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Token " + API_TOKEN))
                .andRespond(withSuccess(trackingJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("DEL-WB-998877");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(delivery.events()).hasSize(3);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.ARRIVED_AT_DESTINATION);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(delivery.events().get(2).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PENDING);
        server.verify();
    }

    @Test
    @DisplayName("synthesizes PICKED_UP event before DELIVERED when Scans array is empty")
    void getStatus_delivered_synthesizesEvents() {
        String deliveredJson = """
                {
                  "ShipmentData": [
                    {
                      "Shipment": {
                        "Status": {
                          "Status": "Delivered"
                        }
                      }
                    }
                  ]
                }
                """;

        server.expect(requestTo(BASE_URL + "/api/v1/packages/json/?waybill=DEL-WB-998877"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Token " + API_TOKEN))
                .andRespond(withSuccess(deliveredJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("DEL-WB-998877");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events()).hasSize(2);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        server.verify();
    }

    @Test
    @DisplayName("cancels order with Delhivery")
    void cancel_success() {
        server.expect(requestTo(BASE_URL + "/api/p/edit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Token " + API_TOKEN))
                .andExpect(jsonPath("$.waybill").value("DEL-WB-998877"))
                .andExpect(jsonPath("$.cancellation").value("true"))
                .andRespond(withSuccess("{\"status\":\"Success\"}", MediaType.APPLICATION_JSON));

        client.cancel("DEL-WB-998877", "Store cancelled");
        server.verify();
    }

    @Test
    @DisplayName("throttles calls when rate limit RPS is exhausted")
    void throttlesWhenRateLimitExceeded() {
        properties.setRateLimitRps(0);
        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        var rateLimitedClient = new DelhiveryApiClient(properties, builder, new ObjectMapper());

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
