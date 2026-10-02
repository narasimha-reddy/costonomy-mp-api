package com.costonomy.mp.delivery.provider.loadshare;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LoadshareApiClientContractTest {

    private LoadshareProperties properties;
    private MockRestServiceServer server;
    private LoadshareApiClient client;

    private static final String BASE_URL = "https://api-staging.loadshare.net";
    private static final String CUSTOMER_CODE = "TEST_CUST_100";
    private static final String AUTH_TOKEN = "secret-loadshare-token";

    @BeforeEach
    void setUp() {
        properties = new LoadshareProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setCustomerCode(CUSTOMER_CODE);
        properties.setAuthToken(AUTH_TOKEN);
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);

        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        client = new LoadshareApiClient(properties, builder, new ObjectMapper());
        server = customizer.getServer();
    }

    private String expectedChecksum(String orderId) throws Exception {
        String input = AUTH_TOKEN + "|" + CUSTOMER_CODE + "|" + (orderId != null ? orderId : "");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
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
                101L, "ls_q_101_dummy",
                "Indiranagar, Bengaluru, 560038",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                "Store Desk", "+919876500000",
                "Koramangala, Bengaluru, 560034",
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-ls-101",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("calculates quote returning real carrier fare from checkServiceability")
    void calculateQuote_success() throws Exception {
        String serviceabilityJson = """
                {
                  "serviceable": true,
                  "fare": {
                    "value": 48.50,
                    "unit": "INR"
                  },
                  "promisedSlaInEpoch": {
                    "total": %d
                  },
                  "predictedDistanceInMetre": 4800
                }
                """.formatted(System.currentTimeMillis() + 1800000L);

        String expectedCheckOrderId = "chk-101";

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/checkServiceability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum(expectedCheckOrderId)))
                .andExpect(jsonPath("$.orderId").value(expectedCheckOrderId))
                .andExpect(jsonPath("$.tasks[0].type").value("PICK_UP"))
                .andExpect(jsonPath("$.tasks[1].type").value("DROP"))
                .andRespond(withSuccess(serviceabilityJson, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("48.50");
        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.distanceKm()).isEqualTo(4.8);
        assertThat(quote.providerQuoteId()).startsWith("ls_q_");
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
    @DisplayName("declines quote when LoadShare checkServiceability returns serviceable=false")
    void calculateQuote_unserviceableFromCarrier() throws Exception {
        String unserviceableJson = """
                {
                  "serviceable": false,
                  "reason": "No rider available in area"
                }
                """;

        String expectedCheckOrderId = "chk-101";

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/checkServiceability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum(expectedCheckOrderId)))
                .andRespond(withSuccess(unserviceableJson, MediaType.APPLICATION_JSON));

        var quote = client.calculateQuote(quoteRequest());

        assertThat(quote.serviceable()).isFalse();
        assertThat(quote.declineReason()).contains("declined route");
        server.verify();
    }

    @Test
    @DisplayName("throws LoadshareContractException when checkServiceability response is missing fare")
    void calculateQuote_missingFareThrowsContractException() throws Exception {
        String noFareJson = """
                {
                  "serviceable": true
                }
                """;

        String expectedCheckOrderId = "chk-101";

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/checkServiceability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum(expectedCheckOrderId)))
                .andRespond(withSuccess(noFareJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(LoadshareContractException.class)
                .hasMessageContaining("missing fare value");
        server.verify();
    }

    @Test
    @DisplayName("creates order with LoadShare and returns Booking")
    void createOrder_success() throws Exception {
        String createResponseJson = """
                {
                  "orderId": "LS-ORDER-9999",
                  "fare": {
                    "value": 48.50
                  }
                }
                """;

        String expectedOrderId = "mp-ls-101";

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum(expectedOrderId)))
                .andExpect(jsonPath("$.orderId").value(expectedOrderId))
                .andExpect(jsonPath("$.tasks[0].address.phoneNumber").value("+919876500000"))
                .andExpect(jsonPath("$.tasks[1].address.phoneNumber").value("+919876511111"))
                .andRespond(withSuccess(createResponseJson, MediaType.APPLICATION_JSON));

        var booking = client.createOrder(bookingRequest());

        assertThat(booking.providerDeliveryId()).isEqualTo("LS-ORDER-9999");
        assertThat(booking.amount()).isEqualByComparingTo("48.50");
        assertThat(booking.trackingUrl()).contains("LS-ORDER-9999");
        server.verify();
    }

    @Test
    @DisplayName("polls tracking status and maps current status and status history events")
    void getStatus_success() throws Exception {
        String trackingJson = """
                {
                  "orderId": "LS-ORDER-9999",
                  "status": "in_transit",
                  "riderDetails": {
                    "name": "Ramesh Kumar",
                    "phone": "+919876543210",
                    "vehicleNumber": "KA-01-LS-1234"
                  },
                  "statusHistory": [
                    {
                      "status": "assigned",
                      "description": "Rider assigned",
                      "timestamp": "1727870000000"
                    },
                    {
                      "status": "picked_up",
                      "description": "Picked up from outlet",
                      "timestamp": "1727871000000"
                    },
                    {
                      "status": "in_transit",
                      "description": "Out for delivery",
                      "timestamp": "1727872000000"
                    }
                  ]
                }
                """;

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/LS-ORDER-9999/track"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum("LS-ORDER-9999")))
                .andRespond(withSuccess(trackingJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("LS-ORDER-9999");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(delivery.driverName()).isEqualTo("Ramesh Kumar");
        assertThat(delivery.driverPhone()).isEqualTo("+919876543210");
        assertThat(delivery.driverVehicle()).isEqualTo("KA-01-LS-1234");
        assertThat(delivery.events()).hasSize(3);
        // Events are processed in reverse order (newest first)
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        assertThat(delivery.events().get(2).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED);
        server.verify();
    }

    @Test
    @DisplayName("synthesizes PICKED_UP event before DELIVERED when statusHistory is empty")
    void getStatus_delivered_synthesizesEvents() throws Exception {
        String deliveredJson = """
                {
                  "orderId": "LS-ORDER-9999",
                  "status": "delivered"
                }
                """;

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/LS-ORDER-9999/track"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum("LS-ORDER-9999")))
                .andRespond(withSuccess(deliveredJson, MediaType.APPLICATION_JSON));

        var delivery = client.getStatus("LS-ORDER-9999");

        assertThat(delivery.status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events()).hasSize(2);
        assertThat(delivery.events().get(0).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.DELIVERED);
        assertThat(delivery.events().get(1).status()).isEqualTo(DeliveryProvider.ProviderDeliveryStatus.PICKED_UP);
        server.verify();
    }

    @Test
    @DisplayName("polls location successfully when currentLocation is present")
    void location_success() throws Exception {
        String locationJson = """
                {
                  "orderId": "LS-ORDER-9999",
                  "status": "in_transit",
                  "currentLocation": {
                    "latitude": 12.9500,
                    "longitude": 77.6100
                  }
                }
                """;

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/LS-ORDER-9999/track"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum("LS-ORDER-9999")))
                .andRespond(withSuccess(locationJson, MediaType.APPLICATION_JSON));

        var location = client.location("LS-ORDER-9999");

        assertThat(location).isNotNull();
        assertThat(location.latitude()).isEqualByComparingTo("12.9500");
        assertThat(location.longitude()).isEqualByComparingTo("77.6100");
        server.verify();
    }

    @Test
    @DisplayName("cancels order with LoadShare")
    void cancel_success() throws Exception {
        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/LS-ORDER-9999/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum("LS-ORDER-9999")))
                .andExpect(jsonPath("$.cancellationReason").value("Cancelled by restaurant"))
                .andRespond(withSuccess("{\"status\":\"CANCELLED\"}", MediaType.APPLICATION_JSON));

        client.cancel("LS-ORDER-9999", "Cancelled by restaurant");
        server.verify();
    }

    @Test
    @DisplayName("throws retryable DeliveryProviderException on server 500 error")
    void serverError_throwsRetryableException() throws Exception {
        String expectedCheckOrderId = "chk-101";

        server.expect(requestTo(BASE_URL + "/hyperlocal/v2/order/checkServiceability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Customer-Code", CUSTOMER_CODE))
                .andExpect(header("Checksum", expectedChecksum(expectedCheckOrderId)))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .satisfies(ex -> assertThat(((DeliveryProviderException) ex).retryable()).isTrue());
        server.verify();
    }

    @Test
    @DisplayName("throttles calls when rate limit RPS is exhausted")
    void throttlesWhenRateLimitExceeded() {
        properties.setRateLimitRps(0); // Immediately exhausted
        var customizer = new MockServerRestTemplateCustomizer();
        var builder = new RestTemplateBuilder().additionalCustomizers(customizer);
        var rateLimitedClient = new LoadshareApiClient(properties, builder, new ObjectMapper());

        assertThatThrownBy(() -> rateLimitedClient.calculateQuote(quoteRequest()))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("rate limit exceeded")
                .satisfies(ex -> assertThat(((DeliveryProviderException) ex).retryable()).isTrue());
    }
}
