package com.costonomy.mp.identity.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.identity.service.MeService;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final MeService meService;
    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "The current user and their memberships",
            description = """
                    The client routes on `memberships` — restaurant or supplier
                    experience is decided here, by the server, never inferred locally.

                    Sign-in itself is costonomy-common-api's (mp-mobile client: phone + code).
                    """)
    public ApiResponse<AuthDtos.MeResponse> me() {
        return ApiResponse.ok(meService.me(ActorContext.requireUserId()));
    }
}
