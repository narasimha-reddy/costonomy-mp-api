package com.costonomy.mp.access.service;

import com.costonomy.mp.access.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The role → permission mapping, cached.
 *
 * <p>Reference data: a few dozen roles, a few hundred mappings, changing only
 * when operations edits the catalogue. Every authorization check reads it, so
 * hitting the database each time would put a join on the hot path of every
 * request for data that is effectively static.
 *
 * <p><b>This is a cache of reference data, not of authorization decisions.</b>
 * Who holds which role, and in which scope, is read live from {@code user_role}
 * on every check. That distinction is what keeps doc 46's "permission revoked
 * while screen open → server rejects" true: revoking a user's grant takes effect
 * on their next request, because the grant is never cached.
 *
 * <p>Held in an {@link AtomicReference} to an immutable map, so {@link #refresh()}
 * swaps the whole snapshot atomically and a concurrent reader sees either the old
 * map or the new one, never a half-built one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RolePermissionCatalog {

    private final RolePermissionRepository repository;
    private final AtomicReference<Map<String, Set<String>>> byRoleCode =
            new AtomicReference<>(Map.of());

    /** Permission codes granted by a role. Empty for an unknown role. */
    public Set<String> permissionsForRole(String roleCode) {
        Map<String, Set<String>> snapshot = byRoleCode.get();
        if (snapshot.isEmpty()) {
            refresh();
            snapshot = byRoleCode.get();
        }
        return snapshot.getOrDefault(roleCode, Set.of());
    }

    /**
     * Reload from the database.
     *
     * <p>Called lazily on first use rather than from an {@code @EventListener} on
     * startup, because Flyway may still be applying V5 when the context refreshes
     * and an empty snapshot cached at that moment would deny everything.
     */
    @Transactional(readOnly = true)
    public void refresh() {
        Map<String, Set<String>> rebuilt = new HashMap<>();
        for (Object[] row : repository.findAllMappings()) {
            String roleCode = (String) row[1];
            String permissionCode = (String) row[2];
            rebuilt.computeIfAbsent(roleCode, k -> new HashSet<>()).add(permissionCode);
        }

        Map<String, Set<String>> immutable = new HashMap<>();
        rebuilt.forEach((role, permissions) -> immutable.put(role, Set.copyOf(permissions)));

        byRoleCode.set(Map.copyOf(immutable));
        log.debug("Role/permission catalog loaded: {} roles", immutable.size());
    }
}
