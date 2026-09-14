package com.costonomy.mp.identity.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates access tokens.
 *
 * <p>Access tokens are short-lived (15 minutes by default) and carry only an
 * identity — the user id and phone. **They deliberately do not carry roles or
 * permissions.**
 *
 * <p>That is worth being explicit about, because embedding them is the obvious
 * optimisation. In a marketplace it is the wrong trade: permissions are granted
 * per scope (this outlet, that supplier store), they change — a purchase manager
 * is removed, a supplier store is suspended — and a token minted before the
 * change would keep working until it expired. Doc 46 ("permission revoked while
 * screen open → server rejects") and doc 03 §16 both require the server to decide
 * on the live state, so authorisation is resolved per request from the database.
 */
@Service
@Slf4j
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final Duration accessTokenTtl;

    public JwtService(
            @Value("${costonomy.mp.jwt.secret}") String secret,
            @Value("${costonomy.mp.jwt.issuer:costonomy-mp}") String issuer,
            @Value("${costonomy.mp.jwt.access-token-ttl:15m}") Duration accessTokenTtl) {

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Fail at startup rather than signing with a weak key. HS256 needs
            // at least 256 bits; a shorter secret is a forgeable token.
            throw new IllegalStateException(
                    "costonomy.mp.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
    }

    public String issueAccessToken(Long userId, String phone) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(issuer)
                .claim("phone", phone)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * Parse and verify, or empty if the token is unusable for any reason.
     *
     * <p>Returns {@link Optional} rather than throwing because an invalid token is
     * an ordinary event — an expired session, a stale client — not an exceptional
     * one, and the filter's job is simply to leave the request unauthenticated.
     */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (ExpiredJwtException ex) {
            log.debug("Access token expired");
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers a bad signature, a malformed token and a wrong issuer. The
            // message is not logged at a level that would fill logs with noise
            // from scanners hitting the API with junk bearer tokens.
            log.debug("Rejected access token: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }
}
