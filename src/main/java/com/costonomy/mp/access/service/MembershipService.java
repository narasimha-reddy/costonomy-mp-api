package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.security.access.AccessClient;
import com.costonomy.security.access.AccessSnapshot;
import lombok.RequiredArgsConstructor;
import com.costonomy.mp.identity.common.Directory;
import com.costonomy.security.client.CommonApiClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 * permissions attached. Grants and names are costonomy-common-api's (D-208).
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final AccessClient access;
    private final Directory directory;

    public record Membership(
            ScopeType scopeType,
            Long scopeId,
            String scopeName,
            /** The parent scope's id, so the client can group outlets by restaurant. */
            Long parentScopeId,
            String parentScopeName,
            /** The name of the member's role here in costonomy-common-api (one per scope, D-75). */
            List<String> roles,
            List<String> permissions) {
    }

    /**
     * The scopes the user acts in, from costonomy-common-api's MARKETPLACE access: a business-wide member gets the
     * restaurant or supplier entry, an outlet-level member one entry per outlet or store, an internal operator the
     * platform entry. Names are common's.
     */
    public List<Membership> membershipsOf(Long userId) {
        AccessSnapshot snapshot = access.get(userId, null).filter(AccessSnapshot::usable).orElse(null);
        if (snapshot == null) {
            return List.of();
        }
        List<AccessSnapshot.Org> orgs = snapshot.orgs() == null ? List.of() : snapshot.orgs();
        Set<Long> orgIds = new TreeSet<>();
        Set<Long> outletIds = new TreeSet<>();
        for (AccessSnapshot.Org org : orgs) {
            if ("BUSINESS".equals(org.accessLevel())) {
                orgIds.add(org.orgId());
            } else {
                org.outlets().forEach(o -> outletIds.add(o.outletId()));
            }
        }
        Map<Long, CommonApiClient.AppOrg> namedOrgs = directory.orgs(orgIds);
        Map<Long, CommonApiClient.AppOutlet> namedOutlets = directory.outlets(outletIds);

        List<Membership> memberships = new ArrayList<>();
        for (AccessSnapshot.Org org : orgs) {
            boolean supplier = "SUPPLIER".equals(org.orgType());
            if ("BUSINESS".equals(org.accessLevel())) {
                ScopeType type = supplier ? ScopeType.SUPPLIER : ScopeType.RESTAURANT;
                var named = namedOrgs.get(org.orgId());
                // TreeSet: clients render and diff these, so a stable order avoids spurious "changed" signals.
                memberships.add(new Membership(type, org.orgId(), named == null ? null : named.displayName(), null,
                        null, roleNames(org.role()),
                        List.copyOf(new TreeSet<>(org.permissions() == null ? Set.of() : org.permissions()))));
                continue;
            }
            ScopeType type = supplier ? ScopeType.SUPPLIER_STORE : ScopeType.OUTLET;
            for (AccessSnapshot.Outlet outlet : org.outlets()) {
                // A grant pointing at an outlet common no longer has should not break the login response; it simply
                // has no name.
                var named = namedOutlets.get(outlet.outletId());
                memberships.add(new Membership(type, outlet.outletId(), named == null ? null : named.name(),
                        named == null ? org.orgId() : named.orgId(), named == null ? null : named.orgName(),
                        roleNames(outlet.role() != null ? outlet.role() : org.role()),
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

    private static List<String> roleNames(AccessSnapshot.Role role) {
        return role == null || role.name() == null ? List.of() : List.of(role.name());
    }
}
