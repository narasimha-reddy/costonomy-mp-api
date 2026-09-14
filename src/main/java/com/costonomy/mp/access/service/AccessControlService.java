package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.domain.UserRole;
import com.costonomy.mp.access.repository.RoleRepository;
import com.costonomy.mp.access.repository.UserRoleRepository;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether an actor may do something, here.
 *
 * <p>This is where doc 03 §16 becomes enforceable: <em>"a valid permission does
 * not permit access to an unrelated restaurant, outlet, supplier or order."</em>
 * Every check is a triple — actor, permission, scope — and none of the three is
 * optional.
 *
 * <p>Three properties worth keeping when changing this class:
 *
 * <ol>
 *   <li><b>Grants are read live.</b> Only the role → permission catalogue is
 *       cached. Revoking a user's grant must take effect on their next request,
 *       not when a cache expires (doc 46).</li>
 *   <li><b>Denial is reported as not-found where the resource is addressable.</b>
 *       See {@link #requireScoped}. Telling a caller "that outlet exists but is
 *       not yours" lets them enumerate other tenants' ids.</li>
 *   <li><b>It fails closed.</b> An unknown permission code, a missing role, a
 *       null scope — all deny. That is why {@code Permissions} constants exist:
 *       a typo'd literal would silently deny forever, and the
 *       {@code AccessControlIT} catalogue test exists to catch the drift.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccessControlService {

    private static final String ACTIVE = "ACTIVE";

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionCatalog catalog;
    private final ScopeResolver scopeResolver;

    /**
     * Every permission {@code userId} holds at {@code (scopeType, scopeId)},
     * including those inherited from a parent scope or a platform grant.
     */
    @Transactional(readOnly = true)
    public Set<String> permissionsAt(Long userId, ScopeType scopeType, Long scopeId) {
        if (userId == null || scopeType == null) {
            return Set.of();
        }

        Set<ScopeResolver.ScopeRef> satisfying = satisfyingScopes(scopeType, scopeId);
        Map<Long, String> roleCodes = roleCodesById();

        Set<String> permissions = new HashSet<>();
        for (UserRole grant : userRoleRepository.findByUserIdAndStatus(userId, ACTIVE)) {
            if (!matches(grant, satisfying)) {
                continue;
            }
            String roleCode = roleCodes.get(grant.getRoleId());
            if (roleCode != null) {
                permissions.addAll(catalog.permissionsForRole(roleCode));
            }
        }
        return permissions;
    }

    @Transactional(readOnly = true)
    public boolean has(Long userId, String permission, ScopeType scopeType, Long scopeId) {
        return permissionsAt(userId, scopeType, scopeId).contains(permission);
    }

    /**
     * Assert the actor holds {@code permission} here, or throw {@code FORBIDDEN}.
     *
     * <p>Use when the caller is already known to be inside the tenant — for
     * example, refusing an action on their <em>own</em> outlet. When the scope
     * itself might belong to someone else, use {@link #requireScoped} instead.
     */
    @Transactional(readOnly = true)
    public void require(Long userId, String permission, ScopeType scopeType, Long scopeId) {
        if (!has(userId, permission, scopeType, scopeId)) {
            log.warn("Denied: user={} permission={} scope={}:{}",
                    userId, permission, scopeType, scopeId);
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * Assert access to a resource that belongs to some tenant.
     *
     * <p>Reports a denial as {@code RESOURCE_NOT_FOUND}, not {@code FORBIDDEN}.
     * That is deliberate and is required by doc 09 §3: if changing an id in a URL
     * returned 403 for "exists, not yours" and 404 for "does not exist", a caller
     * could walk the id space and learn exactly which outlets, stores and orders
     * exist on the platform, and roughly how many. Both answers must look the same
     * from outside.
     *
     * <p>The distinction is preserved where it matters — the WARN below records
     * which case it actually was.
     */
    @Transactional(readOnly = true)
    public void requireScoped(
            Long userId, String permission, ScopeType scopeType, Long scopeId, String entityName) {

        if (!has(userId, permission, scopeType, scopeId)) {
            log.warn("Scope violation: user={} permission={} {}={} — reported as not found",
                    userId, permission, entityName, scopeId);
            throw new com.costonomy.mp.common.error.NotFoundException(entityName, scopeId);
        }
    }

    /** Every scope a user holds any grant in. Backs {@code /auth/me} memberships. */
    @Transactional(readOnly = true)
    public List<UserRole> activeGrants(Long userId) {
        return userRoleRepository.findByUserIdAndStatus(userId, ACTIVE);
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * The concrete scopes whose grants satisfy a check at {@code (type, id)}.
     *
     * <p>Combines the type hierarchy ({@link ScopeType#satisfyingScopes()}) with
     * the actual parent id — a grant on {@code RESTAURANT} only helps if it is on
     * <em>this outlet's</em> restaurant.
     */
    private Set<ScopeResolver.ScopeRef> satisfyingScopes(ScopeType scopeType, Long scopeId) {
        Set<ScopeResolver.ScopeRef> scopes = new HashSet<>();
        scopes.add(new ScopeResolver.ScopeRef(scopeType, scopeId));
        // PLATFORM grants have a null id and are checked by type alone.
        scopes.add(new ScopeResolver.ScopeRef(ScopeType.PLATFORM, null));

        scopeResolver.parentOf(scopeType, scopeId).ifPresent(scopes::add);
        return scopes;
    }

    private boolean matches(UserRole grant, Set<ScopeResolver.ScopeRef> satisfying) {
        if (grant.getScopeType() == ScopeType.PLATFORM) {
            // Internal operators. Safe to accept at any scope because no internal
            // role holds a tenant permission — see ScopeType.PLATFORM.
            return true;
        }
        return satisfying.contains(
                new ScopeResolver.ScopeRef(grant.getScopeType(), grant.getScopeId()));
    }

    private Map<Long, String> roleCodesById() {
        Map<Long, String> codes = new HashMap<>();
        roleRepository.findAll().forEach(role -> codes.put(role.getId(), role.getCode()));
        return codes;
    }
}
