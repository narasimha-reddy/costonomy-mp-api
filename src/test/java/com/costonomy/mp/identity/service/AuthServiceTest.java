package com.costonomy.mp.identity.service;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.MembershipService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.domain.Device;
import com.costonomy.mp.identity.domain.DevicePlatform;
import com.costonomy.mp.identity.domain.OtpPurpose;
import com.costonomy.mp.identity.domain.RefreshToken;
import com.costonomy.mp.identity.domain.User;
import com.costonomy.mp.identity.domain.UserStatus;
import com.costonomy.mp.identity.repository.UserRepository;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private OtpService otpService;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private DeviceService deviceService;
    @Mock private AuditService auditService;
    @Mock private MembershipService membershipService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                otpService,
                jwtService,
                refreshTokenService,
                deviceService,
                auditService,
                membershipService);
    }

    @Test
    @DisplayName("requestOtp succeeds for new user and generates challenge")
    void requestOtpNewUser() {
        when(userRepository.findByPhone("+919999000001")).thenReturn(Optional.empty());
        Instant expiresAt = Instant.now().plusSeconds(300);
        when(otpService.request("+919999000001", OtpPurpose.LOGIN))
                .thenReturn(new OtpService.Challenge(expiresAt, Duration.ofSeconds(60), 5));

        var req = new AuthDtos.OtpRequest("9999000001", "IN", OtpPurpose.LOGIN);
        var resp = authService.requestOtp(req);

        assertThat(resp.maskedPhone()).isEqualTo("+91******0001");
        assertThat(resp.expiresAt()).isEqualTo(expiresAt);
        assertThat(resp.resendAfterSeconds()).isEqualTo(60);
        assertThat(resp.maxAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("requestOtp throws FORBIDDEN when existing user is suspended")
    void requestOtpSuspendedUser() {
        var user = new User();
        user.setId(10L);
        user.setPhone("+919999000001");
        user.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByPhone("+919999000001")).thenReturn(Optional.of(user));

        var req = new AuthDtos.OtpRequest("9999000001", "IN", OtpPurpose.LOGIN);
        assertThatThrownBy(() -> authService.requestOtp(req))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).code() == ErrorCode.FORBIDDEN);

        verify(otpService, never()).request(any(), any());
    }

    @Test
    @DisplayName("verifyOtp registers new user and issues tokens")
    void verifyOtpSuccessNewUser() {
        when(userRepository.findByPhone("+919999000001")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var device = new Device();
        device.setId(99L);
        when(deviceService.register(eq(42L), any())).thenReturn(device);

        var tokenRecord = new RefreshToken();
        tokenRecord.setUserId(42L);
        tokenRecord.setDeviceId(99L);
        when(refreshTokenService.issue(42L, 99L))
                .thenReturn(new RefreshTokenService.IssuedToken("mock-refresh-token-12345678", tokenRecord));
        when(jwtService.issueAccessToken(42L, "+919999000001")).thenReturn("mock-jwt-token");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        var deviceReq = new AuthDtos.DeviceRegistration(DevicePlatform.ANDROID, "fcm-tok", "1.0", "Pixel", "14");
        var req = new AuthDtos.OtpVerifyRequest("9999000001", "IN", "123456", OtpPurpose.LOGIN, deviceReq);

        var session = authService.verifyOtp(req);

        assertThat(session.accessToken()).isEqualTo("mock-jwt-token");
        assertThat(session.refreshToken()).isEqualTo("mock-refresh-token-12345678");
        assertThat(session.expiresInSeconds()).isEqualTo(900);
        assertThat(session.deviceId()).isEqualTo(99L);
        assertThat(session.user().id()).isEqualTo(42L);
        assertThat(session.user().phone()).isEqualTo("+919999000001");

        verify(otpService).verify("+919999000001", OtpPurpose.LOGIN, "123456");
        verify(auditService).record(42L, null, "USER_LOGIN", "USER", 42L, null, null, null, "AUTH");
    }

    @Test
    @DisplayName("verifyOtp handles concurrent user creation race safely")
    void verifyOtpHandlesCreationRace() {
        var existingWinner = new User();
        existingWinner.setId(77L);
        existingWinner.setPhone("+919999000001");
        existingWinner.setStatus(UserStatus.ACTIVE);

        when(userRepository.findByPhone("+919999000001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingWinner));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key users.phone"));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var tokenRecord = new RefreshToken();
        tokenRecord.setUserId(77L);
        when(refreshTokenService.issue(77L, null))
                .thenReturn(new RefreshTokenService.IssuedToken("mock-refresh-token", tokenRecord));
        when(jwtService.issueAccessToken(77L, "+919999000001")).thenReturn("mock-jwt");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        var req = new AuthDtos.OtpVerifyRequest("9999000001", "IN", "123456", OtpPurpose.LOGIN, null);
        var session = authService.verifyOtp(req);

        assertThat(session.user().id()).isEqualTo(77L);
    }

    @Test
    @DisplayName("refresh rotates token and issues new access token")
    void refreshSuccess() {
        var tokenRecord = new RefreshToken();
        tokenRecord.setUserId(42L);
        tokenRecord.setDeviceId(101L);
        when(refreshTokenService.rotate("raw-refresh-token"))
                .thenReturn(new RefreshTokenService.IssuedToken("rotated-raw-token", tokenRecord));

        var user = new User();
        user.setId(42L);
        user.setPhone("+919999000001");
        user.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        when(jwtService.issueAccessToken(42L, "+919999000001")).thenReturn("new-access-token");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        var session = authService.refresh("raw-refresh-token");

        assertThat(session.accessToken()).isEqualTo("new-access-token");
        assertThat(session.refreshToken()).isEqualTo("rotated-raw-token");
        assertThat(session.deviceId()).isEqualTo(101L);
        assertThat(session.user().id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("refresh throws UNAUTHENTICATED when user does not exist")
    void refreshUserNotFound() {
        var tokenRecord = new RefreshToken();
        tokenRecord.setUserId(42L);
        when(refreshTokenService.rotate("raw-refresh-token"))
                .thenReturn(new RefreshTokenService.IssuedToken("rotated-raw-token", tokenRecord));

        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("raw-refresh-token"))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).code() == ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("logout revokes refresh token and records audit event")
    void logoutSuccess() {
        authService.logout(42L, "raw-refresh-token");

        verify(refreshTokenService).revoke("raw-refresh-token", "USER_LOGOUT");
        verify(auditService).record(42L, null, "USER_LOGOUT", "USER", 42L, null, null, null, "AUTH");
    }

    @Test
    @DisplayName("me returns user profile and resolved membership scopes")
    void meSuccess() {
        var user = new User();
        user.setId(42L);
        user.setName("Test User");
        user.setEmail("user@example.com");
        user.setPhone("+919999000001");
        user.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        var membership = new MembershipService.Membership(
                ScopeType.OUTLET, 10L, "Main Outlet",
                1L, "Restaurant Parent",
                List.of("MANAGER"), List.of("ORDER_WRITE"));
        when(membershipService.membershipsOf(42L)).thenReturn(List.of(membership));

        var meResponse = authService.me(42L);

        assertThat(meResponse.user().id()).isEqualTo(42L);
        assertThat(meResponse.user().name()).isEqualTo("Test User");
        assertThat(meResponse.memberships()).hasSize(1);
        assertThat(meResponse.memberships().get(0).scopeType()).isEqualTo("OUTLET");
        assertThat(meResponse.memberships().get(0).scopeId()).isEqualTo(10L);
    }
}
