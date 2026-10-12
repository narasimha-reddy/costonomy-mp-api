package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.security.access.AccessClient;
import com.costonomy.security.access.AccessSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
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

    private final AccessClient access;
    private final JdbcTemplate jdbc;
    private final RoleResolver roles;

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

    /**
     * The scopes the user acts in, from costonomy-common-api's MARKETPLACE access: a business-wide member gets the
     * restaurant or supplier entry, an outlet-level member one entry per outlet or store, an internal operator the
     * platform entry. Names come from MP's copies (CommonSync keeps them current).
     */
    @Transactional(readOnly = true)
    public List<Membership> membershipsOf(Long userId) {
        AccessSnapshot snapshot = access.get(userId, null).filter(AccessSnapshot::usable).orElse(null);
        if (snapshot == null) {
            return List.of();
        }
        List<Membership> memberships = new ArrayList<>();
        for (AccessSnapshot.Org org : snapshot.orgs() == null ? List.<AccessSnapshot.Org>of() : snapshot.orgs()) {
            boolean supplier = "SUPPLIER".equals(org.orgType());
            if ("BUSINESS".equals(org.accessLevel())) {
                ScopeType type = supplier ? ScopeType.SUPPLIER : ScopeType.RESTAURANT;
                var named = nameOf(type, org.orgId());
                // TreeSet: clients render and diff these, so a stable order avoids spurious "changed" signals.
                memberships.add(new Membership(type, org.orgId(), named.name(), named.parentId(), named.parentName(),
                        roles.fromNames(org.orgType(), org.roles()),
                        List.copyOf(new TreeSet<>(org.permissions() == null ? Set.of() : org.permissions()))));
                continue;
            }
            ScopeType type = supplier ? ScopeType.SUPPLIER_STORE : ScopeType.OUTLET;
            for (AccessSnapshot.Outlet outlet : org.outlets()) {
                var named = nameOf(type, outlet.outletId());
                String role = roles.strongest(org.orgType(), outlet.permissions(), outlet.roleLevels());
                memberships.add(new Membership(type, outlet.outletId(), named.name(), named.parentId(),
                        named.parentName(), role == null ? List.of() : List.of(role),
                        List.copyOf(new TreeSet<>(outlet.permissions() == null ? Set.of() : outlet.permissions()))));
            }
        }
        if (!snapshot.platformPermissions().isEmpty()) {
            var platformRoles = snapshot.platform() == null || snapshot.platform().roles() == null
                    ? List.<String>of() : snapshot.platform().roles();
            memberships.add(new Membership(ScopeType.PLATFORM, null, "Costonomy Marketplace", null, null,
                    List.copyOf(new TreeSet<>(platformRoles)), List.copyOf(new TreeSet<>(snapshot.platformPermissions()))));
        }
        return memberships;
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
