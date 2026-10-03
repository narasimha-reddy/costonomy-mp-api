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
    @DisplayName("calculates quote returning real carrier fare from Delhivery invoice charges")
    void calculateQuote_success() {
        String chargesJson = """
                [
                  {
                    "total_amount": 82.50,
                    "gross_amount": 82.50,
                    "charge_DL": 70.00
                  }
                ]
                """;

        server.expect(requestTo(BASE_URL + "/api/kinko/v1/invoice/charges.json?md=S&ss=Delivered&cgm=2500&o_pin=560038&d_pin=560034"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Token " + API_TOKEN))
                .andRespond(withSuccess(chargesJson, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("82.50");
        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.distanceKm()).isNotNull();
        assertThat(quote.providerQuoteId()).startsWith("dlv_q_");
        server.verify();
    }

    @Test
    @DisplayName("declines quote when route exceeds 30 km intra-city radius limit (D-116)")
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
    @DisplayName("throws DelhiveryContractException when invoice charges response missing total amount")
    void calculateQuote_missingChargesThrowsContractException() {
        String noAmountJson = """
                [
                  {
                    "charge_status": "unverified"
                  }
                ]
                """;

        server.expect(requestTo(BASE_URL + "/api/kinko/v1/invoice/charges.json?md=S&ss=Delivered&cgm=2500&o_pin=560038&d_pin=560034"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Token " + API_TOKEN))
                .andRespond(withSuccess(noAmountJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(DelhiveryContractException.class)
                .hasMessageContaining("missing total amount");
        server.verify();
    }

    @Test
    @DisplayName("creates order with Delhivery and returns Booking")
    void createOrder_success() {
        String createResponseJson = """
                {
                  "packages": [
                    {
                      "status": "Success",
                      "waybill": "DEL-WB-998877",
                      "remarks": "Order created"
                    }
                  ]
                }
                """;

        server.expect(requestTo(BASE_URL + "/api/cmu/create.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Token " + API_TOKEN))
                .andExpect(jsonPath("$.shipments[0].order").value("mp-dlv-101"))
                .andExpect(jsonPath("$.shipments[0].pin").value("560034"))
                .andExpect(jsonPath("$.shipments[0].phone").value("+919876511111"))
                .andExpect(jsonPath("$.pickup_location.pin").value("560038"))
                .andRespond(withSuccess(createResponseJson, MediaType.APPLICATION_JSON));

        var booking = client.createOrder(bookingRequest());

        assertThat(booking.providerDeliveryId()).isEqualTo("DEL-WB-998877");
        assertThat(booking.amount()).isEqualByComparingTo("500.00");
        assertThat(booking.trackingUrl()).contains("DEL-WB-998877");
        server.verify();
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
    @DisplayName("throws retryable DeliveryProviderException on server 500 error")
    void serverError_throwsRetryableException() {
        server.expect(requestTo(BASE_URL + "/api/kinko/v1/invoice/charges.json?md=S&ss=Delivered&cgm=2500&o_pin=560038&d_pin=560034"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Token " + API_TOKEN))
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
        var rateLimitedClient = new DelhiveryApiClient(properties, builder, new ObjectMapper());

        assertThatThrownBy(() -> rateLimitedClient.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("rate limit exceeded")
                .satisfies(ex -> assertThat(((DeliveryProviderException) ex).retryable()).isTrue());
    }
}
