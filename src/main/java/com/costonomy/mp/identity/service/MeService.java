package com.costonomy.mp.identity.service;

import com.costonomy.mp.access.service.MembershipService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.domain.User;
import com.costonomy.mp.identity.repository.UserRepository;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user and the scopes they can act in. Sign-in is costonomy-common-api's (phone + code through the
 * mp-mobile client); the users row is common's user under the same id (CommonSync), memberships come from common's
 * MARKETPLACE access.
 */
@Service
@RequiredArgsConstructor
public class MeService {

    private final UserRepository userRepository;
    private final MembershipService membershipService;

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse me(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        // Routing is server-authoritative (§23A.30): restaurant-versus-supplier and the outlet/store switcher are
        // resolved here rather than inferred on the device.
        var memberships = membershipService.membershipsOf(userId).stream()
                .map(m -> new AuthDtos.Membership(
                        m.scopeType().name(), m.scopeId(), m.scopeName(),
                        m.parentScopeId(), m.parentScopeName(),
                        m.roles(), m.permissions()))
                .toList();
        return new AuthDtos.MeResponse(new AuthDtos.UserProfile(user.getId(), user.getPhone(), user.getName(),
                user.getEmail(), user.getStatus().name(), user.getPhoneVerifiedAt()), memberships);
    }
}
