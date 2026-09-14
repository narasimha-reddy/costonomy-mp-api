package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.domain.UserRole;
import com.costonomy.mp.access.repository.RoleRepository;
import com.costonomy.mp.access.repository.UserRoleRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Grants and revokes roles.
 *
 * <p>The only write path into {@code user_role}. Every change is audited — doc 09
 * §7 lists role and permission changes among the things that must be, and a
 * silent grant of {@code PROCUREMENT_APPROVE} is precisely the change someone
 * will need to reconstruct later.
 */
@Service
@RequiredArgsConstructor
public class RoleGrantService {

    private static final String ACTIVE = "ACTIVE";
    private static final String REVOKED = "REVOKED";

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;

    /** Idempotent: re-granting an existing active role is a no-op. */
    @Transactional
    public UserRole grant(Long userId, String roleCode, ScopeType scopeType, Long scopeId, Long grantedBy) {
        var role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Unknown role: " + roleCode));

        var existing = userRoleRepository
                .findByUserIdAndRoleIdAndScopeTypeAndScopeId(userId, role.getId(), scopeType, scopeId);

        if (existing.isPresent()) {
            UserRole grant = existing.get();
            if (ACTIVE.equals(grant.getStatus())) {
                return grant;
            }
            // Reactivate rather than insert: uk_user_role_scope would reject a
            // second row, and reusing the record keeps the grant/revoke history
            // on one id.
            grant.setStatus(ACTIVE);
            grant.setGrantedBy(grantedBy);
            grant.setGrantedAt(Instant.now());
            grant.setRevokedAt(null);
            userRoleRepository.save(grant);
            audit(userId, roleCode, scopeType, scopeId, grantedBy, "ROLE_GRANTED");
            return grant;
        }

        var grant = new UserRole();
        grant.setUserId(userId);
        grant.setRoleId(role.getId());
        grant.setScopeType(scopeType);
        grant.setScopeId(scopeId);
        grant.setStatus(ACTIVE);
        grant.setGrantedBy(grantedBy);
        grant.setGrantedAt(Instant.now());

        UserRole saved = userRoleRepository.save(grant);
        audit(userId, roleCode, scopeType, scopeId, grantedBy, "ROLE_GRANTED");
        return saved;
    }

    @Transactional
    public void revoke(Long userId, String roleCode, ScopeType scopeType, Long scopeId, Long revokedBy) {
        var role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Unknown role: " + roleCode));

        userRoleRepository
                .findByUserIdAndRoleIdAndScopeTypeAndScopeId(userId, role.getId(), scopeType, scopeId)
                .ifPresent(grant -> {
                    grant.setStatus(REVOKED);
                    grant.setRevokedAt(Instant.now());
                    userRoleRepository.save(grant);
                    audit(userId, roleCode, scopeType, scopeId, revokedBy, "ROLE_REVOKED");
                });
    }

    private void audit(Long userId, String roleCode, ScopeType scopeType, Long scopeId,
                       Long actorId, String action) {
        auditService.record(actorId, null, action, "USER_ROLE", userId,
                null, roleCode,
                "%s on %s:%s".formatted(roleCode, scopeType, scopeId), "API");
    }
}
