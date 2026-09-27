package com.costonomy.mp.identity.service;

import com.costonomy.mp.access.service.MembershipService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.domain.Device;
import com.costonomy.mp.identity.domain.OtpPurpose;
import com.costonomy.mp.identity.domain.User;
import com.costonomy.mp.identity.domain.UserStatus;
import com.costonomy.mp.identity.repository.UserRepository;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The login flow. Doc 04 §3, doc 09 §1.
 *
 * <pre>
 * phone → OTP request → verify → user created or found → JWT + refresh token
 * </pre>
 *
 * <p>Registration is implicit: a first-time number becomes a user on successful
 * verification. There is no separate signup, which matches a marketplace where a
 * supplier is invited by phone and a restaurant is onboarded by its owner — a
 * signup form would be a second way to create the same row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final DeviceService deviceService;
    private final AuditService auditService;
    private final MembershipService membershipService;

    @Transactional
    public AuthDtos.OtpRequestResponse requestOtp(AuthDtos.OtpRequest request) {
        log.info("[AUTH_EVENT_SEQ 1/3: OTP_REQUEST_RECEIVED] Initiating OTP request: phone='{}', country='{}', purpose='{}'",
                PhoneNumbers.mask(request.phone()), request.country(), request.purpose());

        String phone = PhoneNumbers.normalize(request.phone(), request.country());
        log.info("[AUTH_EVENT_SEQ 2/3: OTP_REQUEST_VALIDATING] Normalized phone to '{}', verifying account status...",
                PhoneNumbers.mask(phone));

        // Checked before issuing, so a suspended user does not consume SMS budget
        // and does not get a code they cannot use.
        var existingUser = userRepository.findByPhone(phone);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            log.info("[AUTH_EVENT_SEQ 2/3: OTP_REQUEST_VALIDATING] Found existing user id={}, status={}. Checking account usability...",
                    user.getId(), user.getStatus());
            assertUsable(user);
            log.info("[AUTH_EVENT_SEQ 2/3: OTP_REQUEST_VALIDATING] Existing user id={} is active and eligible for OTP challenge",
                    user.getId());
        } else {
            log.info("[AUTH_EVENT_SEQ 2/3: OTP_REQUEST_VALIDATING] No existing user found for phone='{}'. Will register as new user upon verification.",
                    PhoneNumbers.mask(phone));
        }

        var challenge = otpService.request(phone, request.purpose());

        var response = new AuthDtos.OtpRequestResponse(
                challenge.expiresAt(),
                challenge.resendAfter().toSeconds(),
                challenge.maxAttempts(),
                PhoneNumbers.mask(phone));

        log.info("[AUTH_EVENT_SEQ 3/3: OTP_REQUEST_COMPLETED] Generated challenge for phone='{}': expiresAt={}, resendAfterSeconds={}, maxAttempts={}",
                PhoneNumbers.mask(phone), challenge.expiresAt(), challenge.resendAfter().toSeconds(), challenge.maxAttempts());

        return response;
    }

    @Transactional
    public AuthDtos.AuthSession verifyOtp(AuthDtos.OtpVerifyRequest request) {
        log.info("[AUTH_EVENT_SEQ 1/6: OTP_VERIFY_RECEIVED] Verification request received: phone='{}', country='{}', purpose='{}', otp='[PROTECTED]', devicePresent={}",
                PhoneNumbers.mask(request.phone()), request.country(), request.purpose(), request.device() != null);

        String phone = PhoneNumbers.normalize(request.phone(), request.country());
        log.info("[AUTH_EVENT_SEQ 2/6: OTP_VERIFY_CHALLENGE] Verifying OTP challenge for phone='{}', purpose='{}'...",
                PhoneNumbers.mask(phone), request.purpose());

        otpService.verify(phone, request.purpose(), request.otp());
        log.info("[AUTH_EVENT_SEQ 2/6: OTP_VERIFY_CHALLENGE_SUCCESS] OTP challenge successfully verified for phone='{}'",
                PhoneNumbers.mask(phone));

        User user = findOrCreate(phone);
        log.info("[AUTH_EVENT_SEQ 3/6: USER_RESOLVED] User resolved: id={}, phone='{}', status={}. Checking account usability...",
                user.getId(), PhoneNumbers.mask(user.getPhone()), user.getStatus());
        assertUsable(user);

        Instant now = Instant.now();
        Instant prevVerifiedAt = user.getPhoneVerifiedAt();
        Instant prevLoginAt = user.getLastLoginAt();
        user.setPhoneVerifiedAt(now);
        user.setLastLoginAt(now);
        userRepository.save(user);
        log.info("[AUTH_EVENT_SEQ 3/6: USER_STATE_UPDATED] User id={} login state updated: phoneVerifiedAt ({} -> {}), lastLoginAt ({} -> {})",
                user.getId(), prevVerifiedAt, now, prevLoginAt, now);

        Device device = null;
        if (request.device() != null) {
            log.info("[AUTH_EVENT_SEQ 4/6: DEVICE_REGISTERING] Registering device for user id={}: platform={}, appVersion={}, deviceModel={}",
                    user.getId(), request.device().platform(), request.device().appVersion(), request.device().deviceModel());
            device = deviceService.register(
                    user.getId(),
                    new AuthDtos.DeviceRequest(
                            request.device().platform(),
                            request.device().pushToken(),
                            request.device().appVersion(),
                            request.device().deviceModel(),
                            request.device().osVersion()));
        } else {
            log.info("[AUTH_EVENT_SEQ 4/6: DEVICE_SKIPPED] No device payload supplied for user id={}", user.getId());
        }

        Long deviceId = device == null ? null : device.getId();
        log.info("[AUTH_EVENT_SEQ 4/6: DEVICE_RESOLVED] Effective deviceId={} for user id={}", deviceId, user.getId());

        var refreshToken = refreshTokenService.issue(user.getId(), deviceId);
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getPhone());
        long ttlSeconds = jwtService.accessTokenTtl().toSeconds();
        log.info("[AUTH_EVENT_SEQ 5/6: TOKENS_MINTED] Security tokens minted for user id={}: deviceId={}, refreshTokenPrefix='{}', accessTokenTtlSeconds={}",
                user.getId(), deviceId, maskToken(refreshToken.rawToken()), ttlSeconds);

        auditService.record(user.getId(), null, "USER_LOGIN", "USER", user.getId(),
                null, null, null, "AUTH");
        log.info("[AUTH_EVENT_SEQ 5/6: AUDIT_RECORDED] Recorded USER_LOGIN audit event for user id={}", user.getId());

        var session = new AuthDtos.AuthSession(
                accessToken,
                refreshToken.rawToken(),
                ttlSeconds,
                deviceId,
                toProfile(user));

        log.info("[AUTH_EVENT_SEQ 6/6: OTP_VERIFY_COMPLETED] Login session established successfully: userId={}, deviceId={}, status={}",
                user.getId(), deviceId, user.getStatus());

        return session;
    }

    /**
     * Exchange a refresh token for a new session.
     *
     * <p>The user's status is rechecked here, not just at login. A 30-day refresh
     * token issued before a suspension must stop working at the suspension, not
     * when it expires.
     */
    @Transactional
    public AuthDtos.AuthSession refresh(String rawRefreshToken) {
        log.info("[AUTH_EVENT_SEQ 1/4: REFRESH_TOKEN_RECEIVED] Refresh requested with token: prefix='{}' (totalLength={})",
                maskToken(rawRefreshToken), rawRefreshToken != null ? rawRefreshToken.length() : 0);

        var rotated = refreshTokenService.rotate(rawRefreshToken);
        log.info("[AUTH_EVENT_SEQ 2/4: REFRESH_TOKEN_ROTATED] Refresh token rotated: userId={}, deviceId={}, newRefreshTokenPrefix='{}'",
                rotated.record().getUserId(), rotated.record().getDeviceId(), maskToken(rotated.rawToken()));

        User user = userRepository.findById(rotated.record().getUserId())
                .orElseThrow(() -> {
                    log.error("[AUTH_EVENT_SEQ: REFRESH_FAILED] User id={} associated with refresh token not found in database",
                            rotated.record().getUserId());
                    return new BusinessException(ErrorCode.UNAUTHENTICATED);
                });

        log.info("[AUTH_EVENT_SEQ 3/4: USER_CHECKING] Verifying usability for user id={}, status={}...",
                user.getId(), user.getStatus());
        assertUsable(user);

        String accessToken = jwtService.issueAccessToken(user.getId(), user.getPhone());
        long ttlSeconds = jwtService.accessTokenTtl().toSeconds();
        log.info("[AUTH_EVENT_SEQ 3/4: ACCESS_TOKEN_MINTED] Minted fresh access token for user id={} (ttlSeconds={})",
                user.getId(), ttlSeconds);

        var session = new AuthDtos.AuthSession(
                accessToken,
                rotated.rawToken(),
                ttlSeconds,
                rotated.record().getDeviceId(),
                toProfile(user));

        log.info("[AUTH_EVENT_SEQ 4/4: REFRESH_COMPLETED] Session refreshed successfully: userId={}, deviceId={}",
                user.getId(), rotated.record().getDeviceId());

        return session;
    }

    @Transactional
    public void logout(Long userId, String rawRefreshToken) {
        log.info("[AUTH_EVENT_SEQ 1/3: LOGOUT_RECEIVED] Logout initiated: userId={}, tokenPrefix='{}'",
                userId, maskToken(rawRefreshToken));

        refreshTokenService.revoke(rawRefreshToken, "USER_LOGOUT");
        log.info("[AUTH_EVENT_SEQ 2/3: TOKEN_REVOKED] Refresh token revoked for userId={} with reason 'USER_LOGOUT'", userId);

        auditService.record(userId, null, "USER_LOGOUT", "USER", userId,
                null, null, null, "AUTH");
        log.info("[AUTH_EVENT_SEQ 2/3: AUDIT_RECORDED] Recorded USER_LOGOUT audit event for userId={}", userId);

        log.info("[AUTH_EVENT_SEQ 3/3: LOGOUT_COMPLETED] Logout successfully completed for userId={}", userId);
    }

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse me(Long userId) {
        log.info("[AUTH_EVENT_SEQ 1/3: ME_REQUEST_RECEIVED] Resolving identity and access context for userId={}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("[AUTH_EVENT_SEQ: ME_FAILED] User id={} not found", userId);
                    return new BusinessException(ErrorCode.UNAUTHENTICATED);
                });

        // Routing is server-authoritative (§23A.30). Everything the client needs
        // to decide restaurant-versus-supplier, and to render an outlet or store
        // switcher, is resolved here rather than inferred on the device.
        var memberships = membershipService.membershipsOf(userId).stream()
                .map(m -> new AuthDtos.Membership(
                        m.scopeType().name(), m.scopeId(), m.scopeName(),
                        m.parentScopeId(), m.parentScopeName(),
                        m.roles(), m.permissions()))
                .toList();

        log.info("[AUTH_EVENT_SEQ 2/3: MEMBERSHIPS_RESOLVED] userId={}, resolved {} membership scope(s)",
                userId, memberships.size());

        var response = new AuthDtos.MeResponse(toProfile(user), memberships);
        log.info("[AUTH_EVENT_SEQ 3/3: ME_COMPLETED] Returning identity context for userId={}, membershipsCount={}",
                userId, memberships.size());

        return response;
    }

    private User findOrCreate(String phone) {
        var existing = userRepository.findByPhone(phone);
        if (existing.isPresent()) {
            User user = existing.get();
            log.info("[AUTH_EVENT_SEQ: USER_LOOKUP_FOUND] Existing user resolved: id={}, phone='{}', status={}",
                    user.getId(), PhoneNumbers.mask(user.getPhone()), user.getStatus());
            return user;
        }

        log.info("[AUTH_EVENT_SEQ: USER_CREATION_INITIATED] No existing user found for phone='{}', creating new ACTIVE user record",
                PhoneNumbers.mask(phone));
        var user = new User();
        user.setPhone(phone);
        user.setStatus(UserStatus.ACTIVE);
        try {
            User saved = userRepository.saveAndFlush(user);
            log.info("[AUTH_EVENT_SEQ: USER_CREATION_SUCCESS] New user created successfully: id={}, phone='{}', status={}",
                    saved.getId(), PhoneNumbers.mask(saved.getPhone()), saved.getStatus());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            // Two verifications for a brand-new number raced. The unique
            // index on users.phone decided it; read the winner's row.
            log.warn("[AUTH_EVENT_SEQ: USER_CREATION_RACE] Concurrent user creation race detected for phone='{}'. Reading winner record.",
                    PhoneNumbers.mask(phone));
            return userRepository.findByPhone(phone).orElseThrow(() -> {
                log.error("[AUTH_EVENT_SEQ: USER_CREATION_RACE_FAILED] Winner record not found after race for phone='{}'",
                        PhoneNumbers.mask(phone));
                return ex;
            });
        }
    }

    private static void assertUsable(User user) {
        if (!user.isActive()) {
            log.warn("[AUTH_EVENT_SEQ: ACCOUNT_USABILITY_CHECK_FAILED] User id={}, phone='{}', status={} is not ACTIVE. Access denied.",
                    user.getId(), PhoneNumbers.mask(user.getPhone()), user.getStatus());
            // Same error for suspended and deactivated: which one it is is not
            // the caller's business, and distinguishing them would let an
            // attacker enumerate account states.
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "This account isn't active. Please contact support.");
        }
        log.debug("[AUTH_EVENT_SEQ: ACCOUNT_USABILITY_CHECK_PASSED] User id={}, status={} is active",
                user.getId(), user.getStatus());
    }

    private static String maskToken(String token) {
        if (token == null) {
            return "null";
        }
        if (token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 8) + "...";
    }

    private static AuthDtos.UserProfile toProfile(User user) {
        return new AuthDtos.UserProfile(
                user.getId(),
                user.getPhone(),
                user.getName(),
                user.getEmail(),
                user.getStatus().name(),
                user.getPhoneVerifiedAt());
    }
}
