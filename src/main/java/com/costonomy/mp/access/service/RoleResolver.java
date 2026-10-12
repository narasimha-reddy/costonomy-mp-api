package com.costonomy.mp.access.service;

import com.costonomy.mp.identity.common.MarketplaceCatalogPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Marketplace role codes (REST_OWNER, SUP_SALESPERSON…) for a member's costonomy-common-api access.
 *
 * <p>Common names the roles of a business-wide grant ("Owner", "Purchase Manager") and lists only the permissions of
 * an outlet grant. Clients and approval policies speak in Marketplace's codes, so names are matched to codes within
 * the business's side (REST_ / SUP_), and an outlet member is given the strongest role whose whole permission set
 * they hold there.
 */
@Service
@RequiredArgsConstructor
public class RoleResolver {

    /** Strongest first, per side. */
    static final List<String> RESTAURANT = List.of("REST_OWNER", "REST_ADMIN", "REST_PURCHASE_MANAGER",
            "REST_STORE_MANAGER", "REST_FINANCE_STAFF", "REST_RECEIVING_STAFF", "REST_PROCUREMENT_STAFF");
    static final List<String> SUPPLIER = List.of("SUP_OWNER", "SUP_ADMIN", "SUP_STORE_MANAGER", "SUP_SALESPERSON",
            "SUP_FINANCE_STAFF", "SUP_OPERATIONS_STAFF");

    private final JdbcTemplate jdbc;
    private final RolePermissionCatalog catalog;
    private final AtomicReference<Map<String, String>> codeByName = new AtomicReference<>(Map.of());

    /** Codes for the role names of a business-wide grant; names that are not Marketplace roles of that side drop. */
    public List<String> fromNames(String orgType, Collection<String> names) {
        String prefix = "SUPPLIER".equals(orgType) ? "SUP_" : "REST_";
        Set<String> codes = new TreeSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            String code = codes().get(prefix + name.trim().toLowerCase(Locale.ROOT));
            if (code != null) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /** The strongest role of that side whose permissions are all held; level 1 marks the owner. */
    public String strongest(String orgType, Set<String> held, Set<Integer> levels) {
        List<String> order = "SUPPLIER".equals(orgType) ? SUPPLIER : RESTAURANT;
        boolean owner = levels != null && levels.contains(1);
        Set<String> permissions = held == null ? Set.of() : held;
        for (String role : order) {
            if (role.endsWith("_OWNER") && !owner) {
                continue;
            }
            Set<String> needed = catalog.permissionsForRole(role);
            if (!needed.isEmpty() && permissions.containsAll(needed)) {
                return role;
            }
        }
        return null;
    }

    /** "REST_" + lower-case name as common shows it → code, for both sides. */
    private Map<String, String> codes() {
        Map<String, String> map = codeByName.get();
        if (map.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            jdbc.query("SELECT code, name FROM role WHERE code LIKE 'REST\\_%' OR code LIKE 'SUP\\_%'",
                    rs -> {
                        rows.add(new String[] {rs.getString(1), rs.getString(2)});
                    });
            map = rows.stream().collect(Collectors.toMap(
                    r -> r[0].substring(0, r[0].indexOf('_') + 1)
                            + MarketplaceCatalogPublisher.publishedName(r[0], r[1]).toLowerCase(Locale.ROOT),
                    r -> r[0], (a, b) -> a));
            codeByName.set(map);
        }
        return map;
    }
}
