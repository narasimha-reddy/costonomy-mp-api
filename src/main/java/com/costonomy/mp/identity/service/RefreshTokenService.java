package com.costonomy.mp.identity.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.domain.RefreshToken;
import com.costonomy.mp.identity.domain.RefreshTokenStatus;
import com.costonomy.mp.identity.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues, rotates and revokes refresh tokens. Doc 09 §1.
 *
 * <p><b>Rotation.</b> Every refresh both issues a new token and retires the one
 * presented, so a stolen token is usable at most once.
 *
 * <p><b>Reuse detection.</b> Rotation alone is not enough. If an attacker copies
 * a token and the legitimate user refreshes first, the attacker's copy is simply
 * rejected and nobody learns anything. So a token that has already been rotated
 * is treated as evidence of theft — one of the two holders is an attacker, and we
 * cannot tell which — and <b>every</b> token for that user is revoked, forcing a
 * fresh OTP login. It is a deliberately blunt response: the cost is one
 * re-login for a user who may have done nothing wrong, against an attacker
 * retaining access for up to thirty days.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenStore store;

    @Value("${costonomy.mp.jwt.refresh-token-ttl:30d}")
    private Duration refreshTokenTtl;

    /** A refresh token as issued: the raw value goes to the client, the hash to us. */
    public record IssuedToken(String rawToken, RefreshToken record) {
    }

    @Transactional
    public IssuedToken issue(Long userId, Long deviceId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe and unpadded so the value survives headers, query strings and
        // secure storage on both platforms without re-encoding.
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        var token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(raw));
        token.setStatus(RefreshTokenStatus.ACTIVE);
        token.setExpiresAt(Instant.now().plus(refreshTokenTtl));
        token.setDeviceId(deviceId);

        return new IssuedToken(raw, repository.save(token));
    }

    /**
     * Exchange a refresh token for a new one.
     *
     * @return the replacement
     * @throws BusinessException {@code UNAUTHENTICATED} if it is unknown, expired,
     *         revoked, or a replay of an already-rotated token
     */
    @Transactional
    public IssuedToken rotate(String rawToken) {
        RefreshToken presented = repository.findByTokenHash(hash(rawToken))
                // An unknown hash is an invalid token. Nothing to revoke, and
                // nothing more to say to the caller.
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        if (presented.getStatus() == RefreshTokenStatus.ROTATED) {
            // Replay. Either this is a stolen copy, or the legitimate client lost
            // the response to its own refresh and retried. We cannot distinguish
            // them, and the safe reading is theft.
            //
            // Revocation goes through RefreshTokenStore because this method throws
            // immediately afterwards; in the caller's transaction the throw would
            // roll the revocation back and the compromised session would live on.
            log.warn("Refresh token replay detected for user {}; revoking all sessions",
                    presented.getUserId());
            store.revokeAllForUser(presented.getUserId(), "TOKEN_REPLAY_DETECTED");
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        if (!presented.isUsable(Instant.now())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        IssuedToken replacement = issue(presented.getUserId(), presented.getDeviceId());

        presented.setStatus(RefreshTokenStatus.ROTATED);
        presented.setRevokedAt(Instant.now());
        presented.setRevokedReason("ROTATED");
        presented.setReplacedById(replacement.record().getId());
        repository.save(presented);

        return replacement;
    }

    /** Logout. Revoking an already-revoked or unknown token is a no-op, not an error. */
    @Transactional
    public void revoke(String rawToken, String reason) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (token.getStatus() == RefreshTokenStatus.ACTIVE) {
                token.setStatus(RefreshTokenStatus.REVOKED);
                token.setRevokedAt(Instant.now());
                token.setRevokedReason(reason);
                repository.save(token);
            }
        });
    }

    /** Revoke every live session for a user. Delegates to {@link RefreshTokenStore}. */
    public int revokeAllForUser(Long userId, String reason) {
        return store.revokeAllForUser(userId, reason);
    }

    /**
     * SHA-256, hex.
     *
     * <p>A fast hash is correct here, unlike for OTPs: the token is 256 bits of
     * randomness so there is nothing to brute-force, and the lookup must be
     * deterministic because we find the row by the presented value. A per-row
     * salted hash would turn every refresh into a table scan.
     */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public Duration refreshTokenTtl() {
        return refreshTokenTtl;
    }
}
