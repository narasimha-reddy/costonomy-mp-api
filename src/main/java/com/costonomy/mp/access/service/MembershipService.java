package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.repository.RoleRepository;
import com.costonomy.mp.access.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves a user's grants into the memberships {@code /auth/me} returns.
 *
 * <p>This is what the mobile app routes on. §23A.30 and doc 05 §1 require the
 * restaurant-versus-supplier decision to be server-authoritative — the client
 * must never infer it from a phone number, a stored preference, or which screen
 * it happened to be on. So the answer arrives here, fully resolved, with the
 * permissions attached.
 *
 * <p>Scope names are read with {@link JdbcTemplate} rather than through the
 * restaurant and supplier repositories, for the same reason as
 * {@link ScopeResolver}: the access module must not depend on other modules'
 * repositories.
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionCatalog catalog;
    private final JdbcTemplate jdbc;

    /**
     * One entry per scope the user holds a role in.
     *
     * @param scopeType   RESTAURANT, OUTLET, SUPPLIER, SUPPLIER_STORE or PLATFORM
     * @param roles       role codes held at this scope
     * @param permissions everything those roles grant here
     */
    public record Membership(
            ScopeType scopeType,
            Long scopeId,
            String scopeName,
            /** The parent scope's id, so the client can group outlets by restaurant. */
            Long parentScopeId,
            String parentScopeName,
            List<String> roles,
            List<String> permissions) {
    }

    @Transactional(readOnly = true)
    public List<Membership> membershipsOf(Long userId) {
        var grants = userRoleRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (grants.isEmpty()) {
            return List.of();
        }

        Map<Long, String> roleCodes = new HashMap<>();
        roleRepository.findAll().forEach(role -> roleCodes.put(role.getId(), role.getCode()));

        // LinkedHashMap so a user with several grants at one scope gets one entry,
        // and the order stays stable between calls — the client renders an outlet
        // switcher from this and a shuffling list is a bad switcher.
        Map<String, List<String>> rolesByScope = new LinkedHashMap<>();
        Map<String, ScopeKey> keys = new LinkedHashMap<>();

        for (var grant : grants) {
            String key = grant.getScopeType() + ":" + grant.getScopeId();
            String roleCode = roleCodes.get(grant.getRoleId());
            if (roleCode == null) {
                continue;
            }
            keys.putIfAbsent(key, new ScopeKey(grant.getScopeType(), grant.getScopeId()));
            rolesByScope.computeIfAbsent(key, k -> new ArrayList<>()).add(roleCode);
        }

        List<Membership> memberships = new ArrayList<>();
        keys.forEach((key, scope) -> {
            List<String> roles = rolesByScope.getOrDefault(key, List.of());

            // TreeSet: permissions are rendered and diffed by clients, so a
            // deterministic order avoids spurious "changed" signals.
            Set<String> permissions = new TreeSet<>();
            roles.forEach(role -> permissions.addAll(catalog.permissionsForRole(role)));

            var named = nameOf(scope.type(), scope.id());
            memberships.add(new Membership(
                    scope.type(), scope.id(), named.name(),
                    named.parentId(), named.parentName(),
                    List.copyOf(new TreeSet<>(roles)), List.copyOf(permissions)));
        });

        return memberships;
    }

    private record ScopeKey(ScopeType type, Long id) {
    }

    private record NamedScope(String name, Long parentId, String parentName) {
    }

    private NamedScope nameOf(ScopeType type, Long id) {
        if (id == null) {
            return new NamedScope("Costonomy Marketplace", null, null);
        }

        return switch (type) {
            case RESTAURANT -> queryOne(
                    "select name, null, null from restaurant where id = ?", id);
            case OUTLET -> queryOne("""
                    select o.name, r.id, r.name
                      from outlet o join restaurant r on r.id = o.restaurant_id
                     where o.id = ?
                    """, id);
            case SUPPLIER -> queryOne(
                    "select display_name, null, null from supplier_organization where id = ?", id);
            case SUPPLIER_STORE -> queryOne("""
                    select s.name, o.id, o.display_name
                      from supplier_store s join supplier_organization o
                        on o.id = s.supplier_organization_id
                     where s.id = ?
                    """, id);
            case PLATFORM -> new NamedScope("Costonomy Marketplace", null, null);
        };
    }

    private NamedScope queryOne(String sql, Long id) {
        var rows = jdbc.query(sql, (rs, i) -> new NamedScope(
                rs.getString(1),
                rs.getObject(2) == null ? null : rs.getLong(2),
                rs.getString(3)), id);
        // A grant pointing at a deleted scope should not break the login response;
        // it simply has no name.
        return rows.isEmpty() ? new NamedScope(null, null, null) : rows.get(0);
    }
}
