package com.costonomy.mp.identity.common;

import com.costonomy.mp.access.domain.MarketplaceCatalog;
import com.costonomy.mp.access.domain.PermissionCodes;
import com.costonomy.security.CostonomySecurityProperties;
import com.costonomy.security.client.ServiceTokenClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Publishes Marketplace's permissions to costonomy-common-api at startup ({@code PUT
 * /internal/v1/apps/MARKETPLACE/catalog}), so common's MARKETPLACE access carries every code Marketplace checks.
 *
 * <p>The source is {@link MarketplaceCatalog} (what Marketplace's role tables held before D-208), with the codes
 * mapped by {@link PermissionCodes} (D-75). Businesses hold common's roles (the
 * built-in templates or their own); the seeded roles only decide the Staff template's share and the Costonomy staff
 * roles. A failure is logged and the app starts with whatever catalog common already has.
 *
 * <p>It runs on its own thread with a long timeout rather than through the starter's client: common rewrites the
 * templates' permissions in one transaction, which takes far longer than the starter's two-second lookups allow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "costonomy.mp.catalog.publish", havingValue = "true", matchIfMissing = true)
public class MarketplaceCatalogPublisher implements ApplicationRunner {

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

    /**
     * Common's catalog format (D-75): modules, {@code <module>.<action>} permissions with the templates that include
     * them, and Costonomy staff roles. Marketplace's seeded codes are mapped by {@link PermissionCodes}; the ones that
     * are common's own (the business, outlets, team) are not published. The Manager template holds every Marketplace
     * permission, Staff those its seeded staff roles held, Viewer (common adds it) every view.
     */
    Map<String, Object> catalog() {
        record Seeded(String code, String name, String description, String scope) {
        }
        List<Seeded> seeded = new ArrayList<>();
        MarketplaceCatalog.permissions().forEach(p -> seeded.add(new Seeded(p.code(), p.name(), p.description(),
                p.scope())));
        Map<String, List<String>> held = new LinkedHashMap<>();
        Map<String, String> roleScope = new LinkedHashMap<>();
        Map<String, String[]> roleText = new LinkedHashMap<>();
        MarketplaceCatalog.roles().forEach(r -> {
            held.put(r.code(), List.copyOf(r.permissions()));
            roleScope.put(r.code(), r.scope());
            roleText.put(r.code(), new String[] {r.name(), r.description()});
        });

        // What the seeded staff roles could do: the Staff template's share.
        Set<String> staff = new TreeSet<>();
        held.forEach((role, codes) -> {
            if (!"INTERNAL".equals(roleScope.get(role)) && level(role) == 3) {
                codes.forEach(c -> {
                    String n = PermissionCodes.of(c);
                    if (n != null) {
                        staff.add(n);
                    }
                });
            }
        });

        // One entry per new code; two seeded codes can share one (the chat codes of both sides).
        Map<String, Map<String, Object>> permissions = new TreeMap<>();
        Map<String, Set<String>> orgTypes = new LinkedHashMap<>();
        for (Seeded p : seeded) {
            String code = PermissionCodes.of(p.code());
            if (code == null) {
                log.warn("Marketplace permission {} has no access-model code; not published", p.code());
                continue;
            }
            if (PermissionCodes.COMMON_MODULES.contains(PermissionCodes.module(code))) {
                continue;
            }
            boolean platform = "INTERNAL".equals(p.scope());
            Set<String> types = orgTypes.computeIfAbsent(code, k -> new TreeSet<>());
            boolean anyType = !"RESTAURANT".equals(p.scope()) && !"SUPPLIER".equals(p.scope());
            if (anyType || types.contains("ANY")) {
                types.clear();
                types.add("ANY");
            } else {
                types.add(p.scope());
            }
            Map<String, Object> def = permissions.computeIfAbsent(code, k -> new LinkedHashMap<>());
            def.put("code", code);
            def.putIfAbsent("name", name(p.name(), p.code()));
            def.putIfAbsent("description", p.description());
            def.put("isPlatform", platform);
            if (!platform) {
                List<String> templates = new ArrayList<>(List.of("MANAGER"));
                if (staff.contains(code)) {
                    templates.add("STAFF");
                }
                def.put("templates", templates);
            }
        }
        permissions.forEach((code, def) -> {
            Set<String> types = orgTypes.getOrDefault(code, Set.of());
            def.put("orgTypes", types.contains("ANY") || types.size() > 1 ? List.of() : List.copyOf(types));
        });

        List<Map<String, Object>> modules = new ArrayList<>();
        int order = 10;
        Set<String> used = new TreeSet<>();
        permissions.keySet().forEach(c -> used.add(PermissionCodes.module(c)));
        for (Map.Entry<String, String> m : PermissionCodes.MODULES.entrySet()) {
            if (used.remove(m.getKey())) {
                modules.add(Map.of("code", m.getKey(), "name", m.getValue(), "order", order));
                order += 10;
            }
        }
        for (String m : used) {
            modules.add(Map.of("code", m, "name", m, "order", order));
            order += 10;
        }

        // Costonomy staff roles: their platform permissions (common grants those everywhere).
        List<Map<String, Object>> platformRoles = new ArrayList<>();
        roleScope.forEach((role, scope) -> {
            if (!"INTERNAL".equals(scope)) {
                return;
            }
            Set<String> codes = new TreeSet<>();
            held.getOrDefault(role, List.of()).forEach(c -> {
                String n = PermissionCodes.of(c);
                Map<String, Object> def = n == null ? null : permissions.get(n);
                if (def != null && Boolean.TRUE.equals(def.get("isPlatform"))) {
                    codes.add(n);
                }
            });
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", role);
            r.put("name", publishedName(role, roleText.get(role)[0]));
            r.put("description", roleText.get(role)[1]);
            r.put("permissions", List.copyOf(codes));
            platformRoles.add(r);
        });

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("modules", modules);
        catalog.put("permissions", List.copyOf(permissions.values()));
        catalog.put("platformRoles", platformRoles);
        return catalog;
    }

    /** 1 = owner, 2 = admins and managers, 3 = staff (the seeded roles' rank). */
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
