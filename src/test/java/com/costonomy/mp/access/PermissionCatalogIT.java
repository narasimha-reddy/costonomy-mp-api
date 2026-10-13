package com.costonomy.mp.access;

import com.costonomy.mp.access.domain.PermissionCodes;
import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.Roles;
import com.costonomy.mp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the Java constants, the seeded catalogue and the codes common grants (D-75) in step.
 *
 * <p>{@code Permissions} and {@code Roles} are string constants, and a permission
 * check against a code that does not exist silently denies — which fails safe,
 * but denies forever and looks like a puzzling bug rather than a typo. These
 * tests turn that drift into a build failure.
 */
class PermissionCatalogIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    /** A seeded role's permissions, in the seeded codes. */
    private List<String> seededPermissionsOf(String roleCode) {
        return jdbc.queryForList("select p.code from role_permission rp join role r on r.id = rp.role_id "
                + "join permission p on p.id = rp.permission_id where r.code = ?", String.class, roleCode);
    }

    @Test
    @DisplayName("every Permissions constant is the code a seeded permission is published as")
    void permissionConstantsExist() {
        var seeded = jdbc.queryForList("select code from permission", String.class);
        var published = seeded.stream().map(PermissionCodes::of).toList();
        assertThat(constantsOf(Permissions.class))
                .describedAs("a constant no seeded permission maps to would deny silently forever")
                .isSubsetOf(published);
    }

    @Test
    @DisplayName("every seeded permission maps to a <module>.<action> code that has a Java constant")
    void seededPermissionsHaveConstants() {
        // The other direction: a permission nobody can reference from code is
        // dead weight, and usually means a constant or a mapping was forgotten.
        var seeded = jdbc.queryForList("select code from permission", String.class);
        for (String code : seeded) {
            assertThat(PermissionCodes.of(code)).describedAs("%s has no access-model code", code)
                    .isNotNull().matches("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");
        }
        assertThat(seeded.stream().map(PermissionCodes::of).toList()).isSubsetOf(constantsOf(Permissions.class));
    }

    @Test
    @DisplayName("every Roles constant exists in the database")
    void roleConstantsExist() {
        var seeded = jdbc.queryForList("select code from role", String.class);
        assertThat(constantsOf(Roles.class)).isSubsetOf(seeded);
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
        var supplierOnly = jdbc.queryForList(
                "select code from permission where scope = 'SUPPLIER'", String.class);
        var internalOnly = jdbc.queryForList(
                "select code from permission where scope = 'INTERNAL'", String.class);

        for (String roleCode : constantsOf(Roles.class)) {
            if (!roleCode.startsWith("REST_")) {
                continue;
            }
            assertThat(seededPermissionsOf(roleCode))
                    .describedAs("%s must hold no supplier or internal permission", roleCode)
                    .doesNotContainAnyElementsOf(supplierOnly)
                    .doesNotContainAnyElementsOf(internalOnly);
        }
    }

    @Test
    @DisplayName("no supplier role holds a restaurant or internal permission")
    void supplierRolesStayInTheirWorld() {
        var restaurantOnly = jdbc.queryForList(
                "select code from permission where scope = 'RESTAURANT'", String.class);
        var internalOnly = jdbc.queryForList(
                "select code from permission where scope = 'INTERNAL'", String.class);

        for (String roleCode : constantsOf(Roles.class)) {
            if (!roleCode.startsWith("SUP_")) {
                continue;
            }
            assertThat(seededPermissionsOf(roleCode))
                    .describedAs("%s must hold no restaurant or internal permission", roleCode)
                    .doesNotContainAnyElementsOf(restaurantOnly)
                    .doesNotContainAnyElementsOf(internalOnly);
        }
    }

    @Test
    @DisplayName("no internal role holds a tenant permission")
    void internalRolesCannotActAsATenant() {
        // This is what makes ScopeType.PLATFORM safe to accept at any scope: an
        // operator can read any order, but cannot edit anyone's outlet, change a
        // supplier's prices, or place an order as a restaurant.
        var tenantOnly = jdbc.queryForList(
                "select code from permission where scope in ('RESTAURANT', 'SUPPLIER')", String.class);

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
