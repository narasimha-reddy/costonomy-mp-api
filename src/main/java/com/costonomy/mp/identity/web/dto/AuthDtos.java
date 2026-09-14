package com.costonomy.mp.identity.web.dto;

import com.costonomy.mp.identity.domain.DevicePlatform;
import com.costonomy.mp.identity.domain.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request and response shapes for {@code /api/v1/auth} and {@code /api/v1/devices}. */
public final class AuthDtos {

    private AuthDtos() {
    }

    // ── OTP request ──────────────────────────────────────────────────────

    public record OtpRequest(
            @NotBlank(message = "Enter your mobile number")
            String phone,

            /** Defaults to IN. Kept as a parameter so adding a market is config, not code. */
            String country,

            @NotNull(message = "purpose is required")
            OtpPurpose purpose) {
    }

    /**
     * Never contains the code, not even in the mock profile — this shape is
     * logged and cached by clients (doc 09 §16).
     */
    public record OtpRequestResponse(
            Instant expiresAt,
            long resendAfterSeconds,
            int maxAttempts,
            /** Masked, so the OTP screen can show which number it went to (§23A.7). */
            String maskedPhone) {
    }

    // ── OTP verification ─────────────────────────────────────────────────

    public record OtpVerifyRequest(
            @NotBlank(message = "Enter your mobile number")
            String phone,

            String country,

            @NotBlank(message = "Enter the code")
            @Pattern(regexp = "\\d{4,8}", message = "Enter the code")
            String otp,

            @NotNull(message = "purpose is required")
            OtpPurpose purpose,

            /** Optional: registers the device in the same call as login. */
            DeviceRegistration device) {
    }

    public record DeviceRegistration(
            @NotNull DevicePlatform platform,
            @Size(max = 512) String pushToken,
            @Size(max = 32) String appVersion,
            @Size(max = 120) String deviceModel,
            @Size(max = 32) String osVersion) {
    }

    // ── Session ──────────────────────────────────────────────────────────

    public record AuthSession(
            String accessToken,
            String refreshToken,
            /** Seconds until the access token expires; the client refreshes before this. */
            long expiresInSeconds,
            Long deviceId,
            UserProfile user) {
    }

    public record RefreshRequest(
            @NotBlank(message = "refreshToken is required")
            String refreshToken) {
    }

    public record LogoutRequest(
            @NotBlank(message = "refreshToken is required")
            String refreshToken) {
    }

    // ── Identity ─────────────────────────────────────────────────────────

    public record UserProfile(
            Long id,
            String phone,
            String name,
            String email,
            String status,
            Instant phoneVerifiedAt) {
    }

    /**
     * What {@code GET /auth/me} returns.
     *
     * <p>{@code memberships} is what the client routes on (§23A.30: restaurant or
     * supplier experience, decided by the server). It is empty until Phase 4 adds
     * organisations — the field exists now so the contract does not change when it
     * fills in, and so the client is never tempted to infer a role locally.
     */
    public record MeResponse(
            UserProfile user,
            List<Membership> memberships) {
    }

    public record Membership(
            /** RESTAURANT, SUPPLIER or INTERNAL. */
            String scopeType,
            Long scopeId,
            String scopeName,
            List<String> roles,
            List<String> permissions) {
    }

    // ── Devices ──────────────────────────────────────────────────────────

    public record DeviceRequest(
            @NotNull(message = "platform is required")
            DevicePlatform platform,
            @Size(max = 512) String pushToken,
            @Size(max = 32) String appVersion,
            @Size(max = 120) String deviceModel,
            @Size(max = 32) String osVersion) {
    }

    public record DeviceResponse(
            Long id,
            DevicePlatform platform,
            String appVersion,
            String status,
            Instant lastSeenAt) {
    }
}
