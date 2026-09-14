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
 * One OTP challenge. Doc 09 §1.
 *
 * <p>The code is stored <b>hashed with BCrypt</b>, never in plain text, and is
 * never logged (doc 09 §16). BCrypt rather than SHA-256 specifically because the
 * secret is weak: a six-digit code has only a million possible values, so a fast
 * hash would be exhaustible in milliseconds against a leaked table. BCrypt's work
 * factor makes that search impractical, and the cost is irrelevant here because a
 * challenge is verified at most {@code maxAttempts} times.
 */
@Entity
@Table(name = "otp_verification")
@Getter
@Setter
@NoArgsConstructor
public class OtpVerification extends BaseEntity {

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "purpose", nullable = false, length = 32)
    private OtpPurpose purpose;

    @Column(name = "otp_hash", nullable = false, length = 255)
    private String otpHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private OtpStatus status = OtpStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    /**
     * Snapshotted from configuration at issue time rather than read live.
     *
     * <p>So a challenge already in a user's hands keeps the rules it was issued
     * under, even if operations changes the limit while it is outstanding.
     */
    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Which adapter issued it — {@code MSG91} or {@code MOCK}. */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** The provider's reference, so a delivery failure is traceable. */
    @Column(name = "provider_reference", length = 200)
    private String providerReference;

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean hasAttemptsLeft() {
        return attemptCount < maxAttempts;
    }
}
