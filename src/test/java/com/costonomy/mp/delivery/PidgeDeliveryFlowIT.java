package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PidgeDeliveryFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PidgeProperties properties;

    private ApiClient api;
    private static final String TEST_SECRET = "test-pidge-secret-key-32-chars-long";

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        properties.setWebhookSecret(TEST_SECRET);
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private Long createSeedDelivery(String providerDeliveryId) {
        // Create minimal delivery record for testing
        jdbc.update("""
                insert into delivery (supplier_order_id, outlet_id, supplier_store_id, mode, status,
                                      provider_code, provider_delivery_id, fee, currency,
                                      pickup_address, drop_address, requested_at, created_at, updated_at, version)
                values (99001, 1, 1, 'COSTONOMY', 'PROVIDER_SELECTED',
                        'PIDGE', ?, 65.0000, 'INR',
                        'Pickup Point, Indiranagar, Bengaluru', 'Drop Point, Koramangala, Bengaluru',
                        now(6), now(6), now(6), 0)
                """, providerDeliveryId);

        return jdbc.queryForObject(
                "select id from delivery where provider_delivery_id = ? order by id desc limit 1",
                Long.class, providerDeliveryId);
    }

    private String createAdminOperator() throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        Long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = 'OPS_DELIVERY'
                """, userId);
        return token;
    }

    @Test
    void pidgeWebhookIngestionUpdatesStatusAndDriver() throws Exception {
        String providerDeliveryId = "pidg_it_" + System.currentTimeMillis();
        Long deliveryId = createSeedDelivery(providerDeliveryId);

        String payload = """
                {
                    "event_id": "evt-pidge-101",
                    "pidge_delivery_id": "%s",
                    "status": "RIDER_ASSIGNED",
                    "tracking_url": "https://track.pidge.in/live/%s",
                    "timestamp": %d,
                    "driver": {
                        "name": "Rider Vikram",
                        "phone": "+919876543210",
                        "vehicle_number": "KA-01-EQ-1234"
                    }
                }
                """.formatted(providerDeliveryId, providerDeliveryId, System.currentTimeMillis());

        String signature = sign(payload);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/delivery/pidge")
                        .header("X-Pidge-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        // Verify database state updated accurately
        Map<String, Object> deliveryRow = jdbc.queryForMap(
                "select status, driver_name, driver_phone, driver_vehicle, tracking_url from delivery where id = ?",
                deliveryId);

        assertThat(deliveryRow.get("status")).isEqualTo("DRIVER_ASSIGNED");
        assertThat(deliveryRow.get("driver_name")).isEqualTo("Rider Vikram");
        assertThat(deliveryRow.get("driver_phone")).isEqualTo("+919876543210");
        assertThat(deliveryRow.get("driver_vehicle")).isEqualTo("KA-01-EQ-1234");
        assertThat(deliveryRow.get("tracking_url")).isEqualTo("https://track.pidge.in/live/" + providerDeliveryId);
    }

    @Test
    void pidgeWebhookRejectsForgedSignature() throws Exception {
        String payload = """
                {
                    "event_id": "evt-fake",
                    "pidge_delivery_id": "pidg_fake",
                    "status": "DELIVERED"
                }
                """;

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/delivery/pidge")
                        .header("X-Pidge-Signature", "0000000000000000000000000000000000000000000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanInspectDeliveryLedger() throws Exception {
        String providerDeliveryId = "pidg_ledger_" + System.currentTimeMillis();
        Long deliveryId = createSeedDelivery(providerDeliveryId);

        // Seed ledger entry
        jdbc.update("""
                insert into delivery_ledger (delivery_id, provider_code, provider_delivery_id,
                                            entry_type, amount, currency, description, created_at)
                values (?, 'PIDGE', ?, 'BOOKED', 65.0000, 'INR', 'Pidge test booking', now(6))
                """, deliveryId, providerDeliveryId);

        String adminToken = createAdminOperator();

        JsonNode response = api.get(adminToken, "/api/v1/admin/deliveries/" + deliveryId + "/ledger");
        assertThat(response.at("/data")).isNotEmpty();
        assertThat(response.at("/data/0/providerCode").asText()).isEqualTo("PIDGE");
        assertThat(response.at("/data/0/amount").asDouble()).isEqualTo(65.0);
    }
}
