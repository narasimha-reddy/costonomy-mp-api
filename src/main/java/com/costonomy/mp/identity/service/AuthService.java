package com.costonomy.mp.identity.service;

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

    @Transactional
    public AuthDtos.OtpRequestResponse requestOtp(AuthDtos.OtpRequest request) {
        String phone = PhoneNumbers.normalize(request.phone(), request.country());

        // Checked before issuing, so a suspended user does not consume SMS budget
        // and does not get a code they cannot use.
        userRepository.findByPhone(phone).ifPresent(AuthService::assertUsable);

        var challenge = otpService.request(phone, request.purpose());

        return new AuthDtos.OtpRequestResponse(
                challenge.expiresAt(),
                challenge.resendAfter().toSeconds(),
                challenge.maxAttempts(),
                PhoneNumbers.mask(phone));
    }

    @Transactional
    public AuthDtos.AuthSession verifyOtp(AuthDtos.OtpVerifyRequest request) {
        String phone = PhoneNumbers.normalize(request.phone(), request.country());

        otpService.verify(phone, request.purpose(), request.otp());

        User user = findOrCreate(phone);
        assertUsable(user);

        Instant now = Instant.now();
        user.setPhoneVerifiedAt(now);
        user.setLastLoginAt(now);
        userRepository.save(user);

        Device device = request.device() == null ? null : deviceService.register(
                user.getId(),
                new AuthDtos.DeviceRequest(
                        request.device().platform(),
                        request.device().pushToken(),
                        request.device().appVersion(),
                        request.device().deviceModel(),
                        request.device().osVersion()));

        Long deviceId = device == null ? null : device.getId();
        var refreshToken = refreshTokenService.issue(user.getId(), deviceId);
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getPhone());

        auditService.record(user.getId(), null, "USER_LOGIN", "USER", user.getId(),
                null, null, null, "AUTH");

        return new AuthDtos.AuthSession(
                accessToken,
                refreshToken.rawToken(),
                jwtService.accessTokenTtl().toSeconds(),
                deviceId,
                toProfile(user));
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
        var rotated = refreshTokenService.rotate(rawRefreshToken);

        User user = userRepository.findById(rotated.record().getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        assertUsable(user);

        String accessToken = jwtService.issueAccessToken(user.getId(), user.getPhone());

        return new AuthDtos.AuthSession(
                accessToken,
                rotated.rawToken(),
                jwtService.accessTokenTtl().toSeconds(),
                rotated.record().getDeviceId(),
                toProfile(user));
    }

    @Transactional
    public void logout(Long userId, String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken, "USER_LOGOUT");
        auditService.record(userId, null, "USER_LOGOUT", "USER", userId,
                null, null, null, "AUTH");
    }

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        // Memberships arrive in Phase 4 (organisations and authorization). The
        // field is present and empty rather than absent, so the contract the
        // client codes against does not change when it fills in — and so the
        // client never infers a role from anything other than this response
        // (§23A.30: routing is server-authoritative).
        return new AuthDtos.MeResponse(toProfile(user), List.of());
    }

    private User findOrCreate(String phone) {
        return userRepository.findByPhone(phone).orElseGet(() -> {
            var user = new User();
            user.setPhone(phone);
            user.setStatus(UserStatus.ACTIVE);
            try {
                return userRepository.saveAndFlush(user);
            } catch (DataIntegrityViolationException ex) {
                // Two verifications for a brand-new number raced. The unique
                // index on users.phone decided it; read the winner's row.
                return userRepository.findByPhone(phone).orElseThrow(() -> ex);
            }
        });
    }

    private static void assertUsable(User user) {
        if (!user.isActive()) {
            // Same error for suspended and deactivated: which one it is is not
            // the caller's business, and distinguishing them would let an
            // attacker enumerate account states.
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "This account isn't active. Please contact support.");
        }
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
