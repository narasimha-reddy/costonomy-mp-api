package com.costonomy.mp.identity.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.identity.service.AuthService;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication. Doc 04 §3.
 *
 * <p>The OTP endpoints and refresh are public — see {@code SecurityConfig}. They
 * are rate-limited rather than authenticated, because there is no session yet.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/otp/request")
    @SecurityRequirements
    @Operation(
            summary = "Request a one-time code",
            description = """
                    Sends a code by SMS. Subject to a resend cooldown and an hourly
                    request limit; both surface as 429 with a `retryAfterSeconds`
                    detail where applicable.

                    The response never contains the code, in any profile.
                    """)
    public ApiResponse<AuthDtos.OtpRequestResponse> requestOtp(
            @Valid @RequestBody AuthDtos.OtpRequest request) {
        return ApiResponse.ok(authService.requestOtp(request));
    }

    @PostMapping("/otp/verify")
    @SecurityRequirements
    @Operation(
            summary = "Verify a code and start a session",
            description = """
                    On success returns an access token, a refresh token and the user
                    profile. A first-time number becomes a user here — there is no
                    separate signup.

                    Failures: `OTP_INVALID` (with `attemptsRemaining`), `OTP_EXPIRED`,
                    `OTP_ATTEMPTS_EXCEEDED`.
                    """)
    public ApiResponse<AuthDtos.AuthSession> verifyOtp(
            @Valid @RequestBody AuthDtos.OtpVerifyRequest request) {
        return ApiResponse.ok(authService.verifyOtp(request));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
            summary = "Exchange a refresh token for a new session",
            description = """
                    Refresh tokens rotate: the token presented is retired and a new one
                    returned. **Store the new token** — the old one will not work again.

                    Presenting an already-rotated token is treated as evidence of theft
                    and revokes every session for that user.
                    """)
    public ApiResponse<AuthDtos.AuthSession> refresh(
            @Valid @RequestBody AuthDtos.RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "End the session for this refresh token")
    public ApiResponse<Void> logout(@Valid @RequestBody AuthDtos.LogoutRequest request) {
        authService.logout(ActorContext.requireUserId(), request.refreshToken());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "The current user and their memberships",
            description = """
                    The client routes on `memberships` — restaurant or supplier
                    experience is decided here, by the server, never inferred locally.

                    `memberships` is empty until organisations land in Phase 4.
                    """)
    public ApiResponse<AuthDtos.MeResponse> me() {
        return ApiResponse.ok(authService.me(ActorContext.requireUserId()));
    }
}
