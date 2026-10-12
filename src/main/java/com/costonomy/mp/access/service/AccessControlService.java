package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.security.access.AccessClient;
import com.costonomy.security.access.AccessSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
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

    private final AccessClient access;

    /**
     * What {@code userId} may do at this scope, from costonomy-common-api's MARKETPLACE access (cached briefly by
     * the starter, refreshed on access webhooks). Restaurants and supplier organisations are common orgs, outlets
     * and supplier stores are common outlets, under the same ids:
     * <ul>
     *   <li>RESTAURANT / SUPPLIER: the org-level grants only (an outlet grant never reaches the whole org);</li>
     *   <li>OUTLET / SUPPLIER_STORE: the outlet's permissions, which include the org-level grants (inheritance as
     *       before);</li>
     *   <li>platform (internal operator) permissions apply at every scope, as PLATFORM grants did.</li>
     * </ul>
     */
    public Set<String> permissionsAt(Long userId, ScopeType scopeType, Long scopeId) {
        if (userId == null || scopeType == null) {
            return Set.of();
        }
        AccessSnapshot snapshot = access.get(userId, null).filter(AccessSnapshot::usable).orElse(null);
        if (snapshot == null) {
            return Set.of();
        }
        Set<String> permissions = new HashSet<>(snapshot.platformPermissions());
        if (scopeId != null) {
            switch (scopeType) {
                case RESTAURANT, SUPPLIER -> snapshot.org(scopeId)
                        .ifPresent(o -> permissions.addAll(o.permissions() == null ? Set.of() : o.permissions()));
                case OUTLET, SUPPLIER_STORE -> snapshot.outlet(scopeId)
                        .ifPresent(o -> permissions.addAll(o.permissions() == null ? Set.of() : o.permissions()));
                default -> { }
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

    /** {@link #requireScoped} satisfied by holding any one of {@code permissions}. */
    public void requireAnyScoped(
            Long userId, ScopeType scopeType, Long scopeId, String entityName, String... permissions) {

        Set<String> held = permissionsAt(userId, scopeType, scopeId);
        for (String permission : permissions) {
            if (held.contains(permission)) {
                return;
            }
        }
        log.warn("Scope violation: user={} permissions={} {}={} — reported as not found",
                userId, List.of(permissions), entityName, scopeId);
        throw new com.costonomy.mp.common.error.NotFoundException(entityName, scopeId);
    }

    /** Every scope a user holds any grant in. Backs {@code /auth/me} memberships. */
}
