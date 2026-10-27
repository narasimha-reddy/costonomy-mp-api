package com.costonomy.mp.identity.service;

import com.costonomy.mp.access.service.MembershipService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The signed-in user and the scopes they can act in. Sign-in, the user and their memberships are all
 * costonomy-common-api's (D-208): the profile is common's user, memberships its MARKETPLACE access.
 */
@Service
@RequiredArgsConstructor
public class MeService {

    private final Directory directory;
    private final MembershipService membershipService;

    public AuthDtos.MeResponse me(Long userId) {
        var user = directory.user(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        // Routing is server-authoritative (§23A.30): restaurant-versus-supplier and the outlet/store switcher are
        // resolved here rather than inferred on the device.
        var memberships = membershipService.membershipsOf(userId).stream()
                .map(m -> new AuthDtos.Membership(
                        m.scopeType().name(), m.scopeId(), m.scopeName(),
                        m.parentScopeId(), m.parentScopeName(),
                        m.roles(), m.permissions()))
                .toList();
        // Common signs people in with a code sent to their phone, so a phone it holds is a verified one.
        return new AuthDtos.MeResponse(new AuthDtos.UserProfile(user.id(), user.primaryPhone(), user.name(),
                user.primaryEmail(), user.status(), null), memberships);
    }
}
