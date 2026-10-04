package com.costonomy.mp.delivery.provider.pidge;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** D-121: Pidge keeps working, but nothing in a quote or booking is invented. */
class PidgeApiClientContractTest {

    private static final String BASE_URL = "https://pidge.example.invalid";

    private MockRestServiceServer server;
    private PidgeApiClient client;

    @BeforeEach
    void setUp() {
        var properties = new PidgeProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiToken("test-token");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);
        var customizer = new MockServerRestTemplateCustomizer();
        client = new PidgeApiClient(properties,
                new RestTemplateBuilder().additionalCustomizers(customizer), new ObjectMapper());
        server = customizer.getServer();
    }

    private DeliveryProvider.QuoteRequest quoteRequest(BigDecimal weightKg, VehicleType vehicle,
                                                       String dropLat, String dropLng) {
        return new DeliveryProvider.QuoteRequest(101L,
                new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal(dropLat), new BigDecimal(dropLng),
                new BigDecimal("500.00"), weightKg == null ? null : weightKg.multiply(BigDecimal.valueOf(1000)),
                45, weightKg, null, vehicle,
                "Indiranagar, Bengaluru, 560038", "Koramangala, Bengaluru, 560034");
    }

    private DeliveryProvider.QuoteRequest goodQuote() {
        return quoteRequest(new BigDecimal("2.5"), VehicleType.TWO_WHEELER, "12.9352", "77.6245");
    }

    private DeliveryProvider.BookingRequest booking(String quoteId, String pickupContact) {
        return new DeliveryProvider.BookingRequest(
                101L, quoteId,
                "Indiranagar, Bengaluru, 560038", new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                pickupContact, "+919876500000",
                "Koramangala, Bengaluru, 560034", new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Customer Asha", "+919876511111",
                "mp-pidge-101",
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.01), VehicleType.TWO_WHEELER,
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560038"),
                new DeliveryProvider.Locality("Bengaluru", "Karnataka", "560034"),
                new BigDecimal("500.00"));
    }

    private void respondToQuote(String json) {
        server.expect(requestTo(BASE_URL + "/v1/channel/quote"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("a complete quote response is returned as the carrier gave it")
    void quoteSuccess() {
        respondToQuote("""
                {"serviceable": true, "quote_id": "q-9", "total_fare": "61.50",
                 "eta_minutes": 28, "distance_km": 4.8, "expires_in_seconds": 600}""");

        var quote = client.getQuote(goodQuote());

        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("61.50");
        assertThat(quote.providerQuoteId()).isEqualTo("q-9");
        assertThat(quote.etaMinutes()).isEqualTo(28);
        server.verify();
    }

    @Test
    @DisplayName("a quote response without a fare is an error, never a default price")
    void quoteWithoutFareThrows() {
        respondToQuote("""
                {"serviceable": true, "quote_id": "q-9", "eta_minutes": 28,
                 "distance_km": 4.8, "expires_in_seconds": 600}""");

        assertThatThrownBy(() -> client.getQuote(goodQuote()))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("total_fare");
    }

    @Test
    @DisplayName("a quote response without a serviceable flag is an error, not a yes")
    void quoteWithoutServiceableFlagThrows() {
        respondToQuote("""
                {"quote_id": "q-9", "total_fare": "61.50", "eta_minutes": 28,
                 "distance_km": 4.8, "expires_in_seconds": 600}""");

        assertThatThrownBy(() -> client.getQuote(goodQuote()))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("serviceable");
    }

    @Test
    @DisplayName("beyond 30 km, or without a weight or vehicle, the quote is declined without a carrier call")
    void declinedBeforeAnyCall() {
        var far = client.getQuote(quoteRequest(new BigDecimal("2.5"), VehicleType.TWO_WHEELER, "13.5", "78.5"));
        var noWeight = client.getQuote(quoteRequest(null, VehicleType.TWO_WHEELER, "12.9352", "77.6245"));
        var noVehicle = client.getQuote(quoteRequest(new BigDecimal("2.5"), null, "12.9352", "77.6245"));

        assertThat(far.serviceable()).isFalse();
        assertThat(noWeight.serviceable()).isFalse();
        assertThat(noVehicle.serviceable()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("a booking missing its quote id or a contact is refused before any carrier call")
    void bookingRefusedWhenIncomplete() {
        assertThatThrownBy(() -> client.createOrder(booking(null, "Store Desk")))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("quote id");
        assertThatThrownBy(() -> client.createOrder(booking("q-9", " ")))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("pickup contact");
        server.verify();
    }

    @Test
    @DisplayName("a booking response without a fare is an error, never a default fare")
    void bookingWithoutFareThrows() {
        server.expect(requestTo(BASE_URL + "/v1/channel/order/create"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"pidge_delivery_id\": \"d-1\", \"eta_minutes\": 25}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createOrder(booking("q-9", "Store Desk")))
                .isInstanceOf(DeliveryProviderException.class)
                .hasMessageContaining("fare");
    }
}
