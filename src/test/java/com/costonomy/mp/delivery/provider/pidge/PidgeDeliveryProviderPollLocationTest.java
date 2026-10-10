package com.costonomy.mp.delivery.provider.pidge;

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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** B2: the 30 s poll keeps the rider position Pidge's status answer carries, with no second HTTP call. */
class PidgeDeliveryProviderPollLocationTest {

    private static final String BASE_URL = "https://pidge.example.invalid";
    private static final String ORDER = "pidg_poll_1";

    private MockRestServiceServer server;
    private PidgeDeliveryProvider provider;

    @BeforeEach
    void setUp() {
        var properties = new PidgeProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiToken("test-token");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);
        var customizer = new MockServerRestTemplateCustomizer();
        var client = new PidgeApiClient(properties,
                new RestTemplateBuilder().additionalCustomizers(customizer), new ObjectMapper());
        server = customizer.getServer();
        provider = new PidgeDeliveryProvider(client);
    }

    private void respond(String logs) {
        server.expect(requestTo(BASE_URL + "/v1.0/store/channel/vendor/order/" + ORDER))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data": {"id": "%s", "status": "fulfilled", "fulfillment": {"status": "OUT_FOR_PICKUP",
                          "logs": [%s]}}}
                        """.formatted(ORDER, logs), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("poll location from status log")
    void pollLocationFromStatusLog() {
        respond("""
                {"timestamp": "2026-10-06T10:00:00.000Z", "status": "CREATED"},
                {"timestamp": "2026-10-06T10:02:00.000Z", "status": "OUT_FOR_PICKUP",
                 "location": {"latitude": 17.44, "longitude": 78.49},
                 "rider": {"name": "Sunil", "mobile": "9876543210"}}""");

        provider.status(ORDER);
        var location = provider.location(ORDER);

        assertThat(location).isNotNull();
        assertThat(location.latitude()).isEqualByComparingTo(new BigDecimal("17.44"));
        assertThat(location.longitude()).isEqualByComparingTo(new BigDecimal("78.49"));
        assertThat(location.recordedAt()).isEqualTo(Instant.parse("2026-10-06T10:02:00Z"));
        // One GET for status and none for location.
        server.verify();
    }

    @Test
    @DisplayName("null without a log location")
    void nullWithoutALogLocation() {
        respond("""
                {"timestamp": "2026-10-06T10:00:00.000Z", "status": "CREATED"},
                {"timestamp": "2026-10-06T10:02:00.000Z", "status": "OUT_FOR_PICKUP",
                 "rider": {"name": "Sunil", "mobile": "9876543210"}}""");

        var status = provider.status(ORDER);

        assertThat(status.driverName()).isEqualTo("Sunil");
        assertThat(provider.location(ORDER)).isNull();
    }

    @Test
    @DisplayName("null for an order that was never polled")
    void nullBeforeAnyStatus() {
        assertThat(provider.location("never-polled")).isNull();
    }
}
