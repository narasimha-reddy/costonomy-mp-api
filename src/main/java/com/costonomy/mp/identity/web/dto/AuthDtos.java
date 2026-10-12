package com.costonomy.mp.identity.web.dto;

import com.costonomy.mp.identity.domain.DevicePlatform;
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

    // ── me ───────────────────────────────────────────────────────────────

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
     * <p>{@code memberships} is what the client routes on — §23A.30 and doc 05 §1
     * make the restaurant-versus-supplier decision server-authoritative. The client
     * must never infer it from anything else.
     *
     * <p>An empty list means the user has authenticated but belongs to nothing yet:
     * the onboarding case (§23A.8), not an error.
     */
    public record MeResponse(
            UserProfile user,
            List<Membership> memberships) {
    }

    /**
     * One scope the user can act in, with everything the client needs to render
     * an outlet or store switcher without a second call.
     */
    public record Membership(
            /** RESTAURANT, OUTLET, SUPPLIER, SUPPLIER_STORE or PLATFORM. */
            String scopeType,
            Long scopeId,
            String scopeName,
            /** Groups outlets under their restaurant, and stores under their supplier. */
            Long parentScopeId,
            String parentScopeName,
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
