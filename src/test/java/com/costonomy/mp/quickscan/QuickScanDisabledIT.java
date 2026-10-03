package com.costonomy.mp.quickscan;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuickScan with the feature flag left at its default — off. D-106: paying
 * third-party merchants from the wallet needs legal sign-off first, so a
 * restaurant that already holds {@code QUICKSCAN_PAY} must still be refused,
 * and nothing may be debited.
 *
 * <p>A separate class rather than a nested one under {@code QuickScanFlowIT}:
 * the flag is set via {@code @TestPropertySource}, which needs its own Spring
 * context to take effect.
 */
@AutoConfigureMockMvc
class QuickScanDisabledIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    @Test
    @DisplayName("flag off: paying is refused and nothing is debited, even with the wallet funded")
    void disabledRefusesPayment() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                        "name", "Paradise",
                        "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                                "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                                "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        api.post(token, "/api/v1/outlets/" + outletId + "/wallet/top-up", Map.of("amount", "500.00"));

        int status = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/outlets/" + outletId + "/quickscan/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "payeeVpa", "shop@okhdfcbank", "payeeName", "Shop",
                                "amount", "100.00", "note", "test", "method", "WALLET"))))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        assertThat(jdbc.queryForObject(
                "select balance from wallet where outlet_id = ?", BigDecimal.class, outletId))
                .isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject(
                "select count(*) from quickscan_payment where outlet_id = ?", Integer.class, outletId))
                .isZero();
    }

    @Test
    @DisplayName("flag off: the config endpoint says so, with a reason")
    void disabledConfigSaysSo() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                        "name", "Paradise",
                        "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                                "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                                "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();

        var config = api.get(token, "/api/v1/outlets/" + outletId + "/quickscan/config").at("/data");

        assertThat(config.get("enabled").asBoolean()).isFalse();
        assertThat(config.get("methods").get(0).get("method").asText()).isEqualTo("WALLET");
        assertThat(config.get("methods").get(0).get("available").asBoolean()).isFalse();
        assertThat(config.get("methods").get(0).get("reason").asText()).isNotBlank();
    }
}
