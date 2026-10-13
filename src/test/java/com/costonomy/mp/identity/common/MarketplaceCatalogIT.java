package com.costonomy.mp.identity.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.costonomy.mp.access.domain.PermissionCodes;
import com.costonomy.mp.support.AbstractIntegrationTest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The catalog Marketplace publishes to costonomy-common-api (D-75) is one common accepts: every permission is
 * {@code <module>.<action>} of a declared module, common's own codes are left out, the templates hold only tenant
 * permissions, and the Costonomy staff roles only platform ones.
 */
class MarketplaceCatalogIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("the published catalog follows common's catalog rules")
    void catalogIsValid() {
        Map<String, Object> catalog = new MarketplaceCatalogPublisher(jdbc, null, null, null).catalog();
        Set<String> modules = new HashSet<>();
        ((List<Map<String, Object>>) catalog.get("modules")).forEach(m -> modules.add((String) m.get("code")));

        List<Map<String, Object>> permissions = (List<Map<String, Object>>) catalog.get("permissions");
        Set<String> platform = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> p : permissions) {
            String code = (String) p.get("code");
            assertThat(codes.add(code)).describedAs("duplicate %s", code).isTrue();
            assertThat(code).matches("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");
            assertThat(modules).describedAs("module of %s", code).contains(PermissionCodes.module(code));
            assertThat(PermissionCodes.COMMON_MODULES).doesNotContain(PermissionCodes.module(code));
            if (Boolean.TRUE.equals(p.get("isPlatform"))) {
                platform.add(code);
                assertThat(p).doesNotContainKey("templates");
            } else {
                assertThat((List<String>) p.get("templates")).contains("MANAGER");
            }
        }
        assertThat(codes).contains("orders.accept", "procurement.approve", "credit.collect", "wallet.pay",
                "orders.inspect");
        // Staff got what the seeded staff roles held, not approvals or write-offs.
        Map<String, Map<String, Object>> byCode = new java.util.HashMap<>();
        permissions.forEach(p -> byCode.put((String) p.get("code"), p));
        assertThat((List<String>) byCode.get("procurement.create").get("templates")).contains("STAFF");
        assertThat((List<String>) byCode.get("credit.write_off").get("templates")).doesNotContain("STAFF");

        for (Map<String, Object> r : (List<Map<String, Object>>) catalog.get("platformRoles")) {
            assertThat(platform).describedAs("platform role %s", r.get("code"))
                    .containsAll((List<String>) r.get("permissions"));
        }
    }
}
