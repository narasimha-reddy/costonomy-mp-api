package com.costonomy.mp.access;

import com.costonomy.mp.access.domain.MarketplaceCatalog;
import com.costonomy.mp.access.domain.PermissionCodes;
import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.Roles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the Java constants, the seeded catalogue ({@code marketplace-catalog.json}, D-208) and the codes common
 * grants (D-75) in step.
 *
 * <p>{@code Permissions} and {@code Roles} are string constants, and a permission
 * check against a code that does not exist silently denies — which fails safe,
 * but denies forever and looks like a puzzling bug rather than a typo. These
 * tests turn that drift into a build failure.
 */
@Tag("integration")
class PermissionCatalogIT {

    /** A seeded role's permissions, in the seeded codes. */
    private static List<String> seededPermissionsOf(String roleCode) {
        return MarketplaceCatalog.role(roleCode).map(MarketplaceCatalog.SeededRole::permissions).orElse(List.of());
    }

    private static Set<String> seededPermissions(String... scopes) {
        Set<String> wanted = Set.of(scopes);
        return MarketplaceCatalog.permissions().stream().filter(p -> wanted.contains(p.scope()))
                .map(MarketplaceCatalog.SeededPermission::code).collect(Collectors.toSet());
    }

    private static List<String> seededCodes() {
        return MarketplaceCatalog.permissions().stream().map(MarketplaceCatalog.SeededPermission::code).toList();
    }

    @Test
    @DisplayName("every Permissions constant is the code a seeded permission is published as")
    void permissionConstantsExist() {
        var published = seededCodes().stream().map(PermissionCodes::of).toList();
        assertThat(constantsOf(Permissions.class))
                .describedAs("a constant no seeded permission maps to would deny silently forever")
                .isSubsetOf(published);
    }

    @Test
    @DisplayName("every seeded permission maps to a <module>.<action> code that has a Java constant")
    void seededPermissionsHaveConstants() {
        // The other direction: a permission nobody can reference from code is
        // dead weight, and usually means a constant or a mapping was forgotten.
        for (String code : seededCodes()) {
            assertThat(PermissionCodes.of(code)).describedAs("%s has no access-model code", code)
                    .isNotNull().matches("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");
        }
        assertThat(seededCodes().stream().map(PermissionCodes::of).toList())
                .isSubsetOf(constantsOf(Permissions.class));
    }

    @Test
    @DisplayName("every role holds only seeded permissions")
    void rolesHoldSeededPermissions() {
        var seeded = seededCodes();
        for (MarketplaceCatalog.SeededRole role : MarketplaceCatalog.roles()) {
            assertThat(seeded).describedAs("%s holds an unknown permission", role.code())
                    .containsAll(role.permissions());
        }
    }

    @Test
    @DisplayName("every Roles constant is a seeded role")
    void roleConstantsExist() {
        var seeded = MarketplaceCatalog.roles().stream().map(MarketplaceCatalog.SeededRole::code).toList();
        assertThat(constantsOf(Roles.class)).isSubsetOf(seeded);
    }

    @Test
    @DisplayName("the owners hold everything their side's admin holds, and staff roles hold their jobs")
    void seededRolesHoldWhatTheyShould() {
        // Common seeds these as the business's built-in roles; a role silently losing
        // its core permission would lock every business that holds it out of that job.
        assertThat(seededPermissionsOf(Roles.REST_OWNER)).containsAll(seededPermissionsOf(Roles.REST_ADMIN))
                .contains("PROCUREMENT_APPROVE", "OUTLET_USER_MANAGE", "RESTAURANT_EDIT", "QUICKSCAN_PAY");
        assertThat(seededPermissionsOf(Roles.SUP_OWNER)).containsAll(seededPermissionsOf(Roles.SUP_ADMIN))
                .contains("ORDER_ACCEPT", "CREDIT_WRITE_OFF", "SUPPLIER_USER_MANAGE", "SUPPLIER_EDIT");
        assertThat(seededPermissionsOf(Roles.SUP_STORE_MANAGER))
                .contains("ORDER_ACCEPT", "CATALOG_EDIT", "CREDIT_COLLECT", "STORE_EDIT")
                .doesNotContain("CREDIT_WRITE_OFF", "SUPPLIER_USER_MANAGE");
        assertThat(seededPermissionsOf(Roles.REST_PROCUREMENT_STAFF)).contains("PROCUREMENT_CREATE")
                .doesNotContain("PROCUREMENT_APPROVE");
        assertThat(seededPermissionsOf(Roles.REST_RECEIVING_STAFF)).contains("ORDER_RECEIVE");
        assertThat(seededPermissionsOf(Roles.OPS_SUPPLIER_VERIFY)).contains("SUPPLIER_VERIFY");
        assertThat(seededPermissionsOf(Roles.OPS_ADMIN)).containsAll(seededPermissions("INTERNAL"));
    }

    @Test
    @DisplayName("every role grants at least one permission")
    void noRoleIsEmpty() {
        // A role that grants nothing is a role someone will assign and then spend
        // an afternoon wondering why it does nothing.
        for (String roleCode : constantsOf(Roles.class)) {
            assertThat(seededPermissionsOf(roleCode))
                    .describedAs("%s grants nothing", roleCode)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("no restaurant role holds a supplier or internal permission")
    void restaurantRolesStayInTheirWorld() {
        // This is the property that makes the REST_/SUP_ prefix check at the
        // grant endpoints sufficient: even the most privileged restaurant role
        // cannot reach across the marketplace.
        var forbidden = seededPermissions("SUPPLIER", "INTERNAL");
        for (String roleCode : constantsOf(Roles.class)) {
            if (!roleCode.startsWith("REST_")) {
                continue;
            }
            assertThat(seededPermissionsOf(roleCode))
                    .describedAs("%s must hold no supplier or internal permission", roleCode)
                    .doesNotContainAnyElementsOf(forbidden);
        }
    }

    @Test
    @DisplayName("no supplier role holds a restaurant or internal permission")
    void supplierRolesStayInTheirWorld() {
        var forbidden = seededPermissions("RESTAURANT", "INTERNAL");
        for (String roleCode : constantsOf(Roles.class)) {
            if (!roleCode.startsWith("SUP_")) {
                continue;
            }
            assertThat(seededPermissionsOf(roleCode))
                    .describedAs("%s must hold no restaurant or internal permission", roleCode)
                    .doesNotContainAnyElementsOf(forbidden);
        }
    }

    @Test
    @DisplayName("no internal role holds a tenant permission")
    void internalRolesCannotActAsATenant() {
        // This is what makes ScopeType.PLATFORM safe to accept at any scope: an
        // operator can read any order, but cannot edit anyone's outlet, change a
        // supplier's prices, or place an order as a restaurant.
        var tenantOnly = seededPermissions("RESTAURANT", "SUPPLIER");
        for (String roleCode : constantsOf(Roles.class)) {
            if (!roleCode.startsWith("OPS_")) {
                continue;
            }
            assertThat(seededPermissionsOf(roleCode))
                    .describedAs("%s must not be able to act as a restaurant or supplier", roleCode)
                    .doesNotContainAnyElementsOf(tenantOnly);
        }
    }

    private static List<String> constantsOf(Class<?> type) {
        List<String> values = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    values.add((String) field.get(null));
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        return values;
    }
}
