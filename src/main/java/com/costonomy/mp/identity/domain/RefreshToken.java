package com.costonomy.mp.identity.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A refresh token, stored as a SHA-256 hash. Doc 09 §1.
 *
 * <p><b>SHA-256 here, BCrypt for OTPs</b> — the difference is deliberate. A
 * refresh token is 256 bits of randomness, so there is nothing to brute-force
 * and a fast hash is safe; and the lookup must be deterministic, because we find
 * the row <em>by</em> the presented token. BCrypt salts per row, which would make
 * that lookup a full table scan.
 *
 * <p>Tokens rotate: each use revokes the presented token and issues a new one,
 * with {@link #replacedById} recording the chain. That chain is what turns a
 * replayed token from "merely rejected" into a detectable theft signal — see
 * {@code RefreshTokenService}.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private RefreshTokenStatus status = RefreshTokenStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;

    /** The token issued in this one's place, when it was rotated. */
    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "device_id")
    private Long deviceId;

    public boolean isUsable(Instant now) {
        return status == RefreshTokenStatus.ACTIVE && expiresAt.isAfter(now);
    }
}
