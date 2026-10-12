package com.costonomy.mp.identity.common;

import com.costonomy.security.CostonomySecurityProperties;
import com.costonomy.security.client.ServiceTokenClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes Marketplace's roles and permissions to costonomy-common-api at startup ({@code PUT
 * /internal/v1/apps/MARKETPLACE/catalog}), so common's MARKETPLACE access carries every code Marketplace checks.
 *
 * <p>The source is Marketplace's own {@code role}, {@code permission} and {@code role_permission} tables, which its
 * migrations seed; there is no second copy to keep in step. A failure is logged and the app starts with whatever
 * catalog common already has.
 *
 * <p>It runs on its own thread with a long timeout rather than through the starter's client: common rewrites every
 * role's permissions in one transaction, which takes far longer than the starter's two-second lookups allow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "costonomy.mp.catalog.publish", havingValue = "true", matchIfMissing = true)
public class MarketplaceCatalogPublisher implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final ServiceTokenClient tokens;
    private final CostonomySecurityProperties props;
    private final ObjectMapper json;

    @Override
    public void run(ApplicationArguments args) {
        Thread publisher = new Thread(this::publish, "marketplace-catalog-publisher");
        publisher.setDaemon(true);
        publisher.start();
    }

    void publish() {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(props.getCommonApi().base() + "/internal/v1/apps/MARKETPLACE/catalog"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + tokens.token())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(catalog())))
                    .build();
            HttpResponse<String> r = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) {
                log.info("Marketplace catalog published to costonomy-common-api: {}", r.body());
            } else {
                log.error("costonomy-common-api refused the Marketplace catalog: HTTP {} {}", r.statusCode(), r.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Could not publish the Marketplace catalog to costonomy-common-api", e);
        }
    }

    Map<String, Object> catalog() {
        List<Map<String, Object>> permissions = new ArrayList<>();
        jdbc.query("SELECT code, name, description, scope FROM permission ORDER BY code", rs -> {
            Map<String, Object> p = new LinkedHashMap<>();
            String scope = rs.getString("scope");
            p.put("code", rs.getString("code"));
            p.put("kind", "API");
            p.put("name", name(rs.getString("name"), rs.getString("code")));
            p.put("description", rs.getString("description"));
            p.put("isPlatform", "INTERNAL".equals(scope));
            p.put("orgTypes", "RESTAURANT".equals(scope) || "SUPPLIER".equals(scope) ? List.of(scope) : List.of());
            permissions.add(p);
        });

        Map<String, List<String>> held = new LinkedHashMap<>();
        jdbc.query("SELECT r.code, p.code FROM role_permission rp JOIN role r ON r.id = rp.role_id "
                + "JOIN permission p ON p.id = rp.permission_id ORDER BY r.code, p.code", rs -> {
            held.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        });

        List<Map<String, Object>> roles = new ArrayList<>();
        jdbc.query("SELECT code, name, description, scope FROM role WHERE status = 'ACTIVE' ORDER BY code", rs -> {
            String code = rs.getString("code");
            String scope = rs.getString("scope");
            boolean platform = "INTERNAL".equals(scope);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", code);
            r.put("name", publishedName(code, rs.getString("name")));
            r.put("description", rs.getString("description"));
            r.put("permissions", held.getOrDefault(code, List.of()));
            r.put("isPlatform", platform);
            r.put("orgType", platform ? null : scope);
            r.put("assignableScopes", platform ? List.of("PLATFORM")
                    : code.endsWith("_OWNER") ? List.of("ORG") : List.of("ORG", "OUTLET"));
            r.put("level", platform ? null : level(code));
            roles.add(r);
        });

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("permissions", permissions);
        catalog.put("systemRoles", roles);
        return catalog;
    }

    /** 1 = owner, 2 = admins and managers, 3 = staff; what common uses for "who may grant what". */
    public static int level(String code) {
        if (code.endsWith("_OWNER")) {
            return 1;
        }
        return code.endsWith("_ADMIN") || code.endsWith("_MANAGER") ? 2 : 3;
    }

    /**
     * The role's name in common, where names are unique per app: the supplier side reads "Supplier Store Manager"
     * where Marketplace says "Store Manager" (the restaurant side has one too), as common's own seed does.
     */
    public static String publishedName(String code, String name) {
        String n = name(name, code).trim();
        return code.startsWith("SUP_") && !n.startsWith("Supplier ") ? "Supplier " + n : n;
    }

    private static String name(String name, String code) {
        return name == null || name.isBlank() ? code : name;
    }
}
