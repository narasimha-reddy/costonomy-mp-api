package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.pidge.PidgeApiClient;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies platform-scoped permissions and non-production sandbox restrictions for:
 * 1. POST /api/v1/admin/deliveries/providers/stats/aggregate
 * 2. GET /api/v1/admin/deliveries/pidge/sandbox/status
 * 3. POST /api/v1/admin/deliveries/pidge/sandbox/webhook
 */
@AutoConfigureMockMvc
class AdminDeliveryEndpointsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PidgeProperties pidgeProperties;
    @MockBean private PidgeApiClient pidgeApiClient;

    private ApiClient api;
    private String operatorToken;
    private String viewOnlyToken;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        operatorToken = createUserWithRole("OPS_ADMIN");
        viewOnlyToken = createUserWithRole("VIEW_ONLY");
    }

    private String createUserWithRole(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = ?
                """, userId, roleCode);
        return token;
    }

    @Test
    @DisplayName("POST /providers/stats/aggregate: permitted user succeeds, unauthorized is denied")
    void statsAggregate_authorization() throws Exception {
        // 1. Permitted operator with DELIVERY_OPERATE
        int okStatus = api.postStatus(operatorToken, "/api/v1/admin/deliveries/providers/stats/aggregate", Map.of());
        assertThat(okStatus).isEqualTo(200);

        // 2. View-only user denied (403)
        int deniedStatus = api.postStatus(viewOnlyToken, "/api/v1/admin/deliveries/providers/stats/aggregate", Map.of());
        assertThat(deniedStatus).isEqualTo(403);

        // 3. Unauthenticated denied (401)
        int unauthStatus = api.postStatus(null, "/api/v1/admin/deliveries/providers/stats/aggregate", Map.of());
        assertThat(unauthStatus).isEqualTo(401);
    }

    @Test
    @DisplayName("GET /pidge/sandbox/status: requires DELIVERY_OPERATE and non-production sandbox environment")
    void pidgeSandboxStatus_authorizationAndEnvironment() throws Exception {
        when(pidgeApiClient.simulateOrderStatus(anyString(), anyString()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "simulated"));

        // With sandbox enabled
        pidgeProperties.setSandbox(true);

        // Permitted operator succeeds
        int okStatus = api.getStatus(operatorToken, "/api/v1/admin/deliveries/pidge/sandbox/status?pidgeDeliveryId=pid-1&dummyStatus=fulfilled|picked up");
        assertThat(okStatus).isEqualTo(200);

        // View-only operator denied (403)
        int deniedStatus = api.getStatus(viewOnlyToken, "/api/v1/admin/deliveries/pidge/sandbox/status?pidgeDeliveryId=pid-1&dummyStatus=fulfilled|picked up");
        assertThat(deniedStatus).isEqualTo(403);

        // With sandbox disabled (production mode): operator is blocked with 403 Forbidden
        pidgeProperties.setSandbox(false);
        int prodBlocked = api.getStatus(operatorToken, "/api/v1/admin/deliveries/pidge/sandbox/status?pidgeDeliveryId=pid-1&dummyStatus=fulfilled|picked up");
        assertThat(prodBlocked).isEqualTo(403);

        // Restore sandbox property
        pidgeProperties.setSandbox(true);
    }

    @Test
    @DisplayName("POST /pidge/sandbox/webhook: requires DELIVERY_OPERATE and non-production sandbox environment")
    void pidgeSandboxWebhook_authorizationAndEnvironment() throws Exception {
        when(pidgeApiClient.triggerSandboxWebhook(anyString(), anyString()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("result", "webhook_triggered"));

        // With sandbox enabled
        pidgeProperties.setSandbox(true);

        // Permitted operator succeeds
        int okStatus = api.postStatus(operatorToken, "/api/v1/admin/deliveries/pidge/sandbox/webhook?pidgeDeliveryId=pid-1&dummyStatus=fulfilled|picked up", Map.of());
        assertThat(okStatus).isEqualTo(200);

        // View-only operator denied (403)
        int deniedStatus = api.postStatus(viewOnlyToken, "/api/v1/admin/deliveries/pidge/sandbox/webhook?pidgeDeliveryId=pid-1&dummyStatus=fulfilled|picked up", Map.of());
        assertThat(deniedStatus).isEqualTo(403);

        // With sandbox disabled (production mode): operator is blocked with 403 Forbidden
        pidgeProperties.setSandbox(false);
        int prodBlocked = api.postStatus(operatorToken, "/api/v1/admin/deliveries/pidge/sandbox/webhook?pidgeDeliveryId=pid-1&dummyStatus=fulfilled|picked up", Map.of());
        assertThat(prodBlocked).isEqualTo(403);

        // Restore sandbox property
        pidgeProperties.setSandbox(true);
    }
}
