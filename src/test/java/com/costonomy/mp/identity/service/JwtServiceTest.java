package com.costonomy.mp.identity.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "a-test-secret-that-is-at-least-32-bytes-long";

    private JwtService service(Duration ttl) {
        return new JwtService(SECRET, "costonomy-mp", ttl);
    }

    @Test
    @DisplayName("round-trips the identity it was issued with")
    void roundTrip() {
        var jwt = service(Duration.ofMinutes(15));
        var claims = jwt.parse(jwt.issueAccessToken(42L, "+919999000001")).orElseThrow();

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("phone", String.class)).isEqualTo("+919999000001");
    }

    @Test
    @DisplayName("carries identity only — no roles or permissions")
    void carriesNoAuthorities() {
        // Deliberate: permissions are scope-dependent and change while a token is
        // live, so authorisation is resolved per request from the database
        // (doc 03 §16, doc 46 "permission revoked while screen open").
        var jwt = service(Duration.ofMinutes(15));
        var claims = jwt.parse(jwt.issueAccessToken(42L, "+919999000001")).orElseThrow();

        assertThat(claims.keySet())
                .doesNotContain("roles", "permissions", "authorities", "scopes");
    }

    @Test
    @DisplayName("rejects an expired token")
    void rejectsExpired() {
        var issuer = service(Duration.ofSeconds(-1));
        assertThat(issuer.parse(issuer.issueAccessToken(1L, "+919999000001"))).isEmpty();
    }

    @Test
    @DisplayName("rejects a token signed with a different secret")
    void rejectsForgedSignature() {
        var other = new JwtService(
                "a-different-secret-also-at-least-32-bytes!!", "costonomy-mp", Duration.ofMinutes(15));
        var token = other.issueAccessToken(1L, "+919999000001");

        assertThat(service(Duration.ofMinutes(15)).parse(token)).isEmpty();
    }

    @Test
    @DisplayName("rejects a token from a different issuer")
    void rejectsWrongIssuer() {
        // Stops a token minted by another Costonomy service being accepted here.
        var other = new JwtService(SECRET, "some-other-service", Duration.ofMinutes(15));
        assertThat(service(Duration.ofMinutes(15)).parse(other.issueAccessToken(1L, "+91999"))).isEmpty();
    }

    @Test
    @DisplayName("rejects garbage without throwing")
    void rejectsGarbage() {
        var jwt = service(Duration.ofMinutes(15));
        assertThat(jwt.parse("not-a-token")).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
    }

    @Test
    @DisplayName("refuses to start with a secret too short for HS256")
    void refusesWeakSecret() {
        // Failing at startup beats signing forgeable tokens in production.
        assertThatThrownBy(() -> new JwtService("too-short", "costonomy-mp", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
