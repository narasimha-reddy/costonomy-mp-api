package com.costonomy.mp.identity.repository;

import com.costonomy.mp.identity.domain.OtpPurpose;
import com.costonomy.mp.identity.domain.OtpStatus;
import com.costonomy.mp.identity.domain.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {

    /**
     * The live challenge for this phone and purpose.
     *
     * <p>Ordered newest-first because issuing a new code supersedes the previous
     * one; if a stale PENDING row ever survives, the newest is still the right
     * one to verify against.
     */
    Optional<OtpVerification> findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(
            String phone, OtpPurpose purpose, OtpStatus status);

    /** The most recent challenge whatever its state — backs the resend cooldown. */
    Optional<OtpVerification> findFirstByPhoneAndPurposeOrderByCreatedAtDesc(
            String phone, OtpPurpose purpose);

    /** Request-rate throttling (doc 09 §1, §14). */
    long countByPhoneAndCreatedAtAfter(String phone, Instant since);

    List<OtpVerification> findByPhoneAndPurposeAndStatus(
            String phone, OtpPurpose purpose, OtpStatus status);

    /**
     * Consume one attempt, atomically.
     *
     * <p>A single conditional UPDATE rather than a read-modify-write: the
     * {@code attempt_count < max_attempts} guard is evaluated by the database, so
     * concurrent verifications cannot both read the same count and overwrite each
     * other's increment. Returns 0 when no attempts remain.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update otp_verification
               set attempt_count = attempt_count + 1
             where id = :id
               and attempt_count < max_attempts
            """, nativeQuery = true)
    int incrementAttemptCount(@Param("id") Long id);

    /** Housekeeping: retire challenges nobody redeemed. */
    @Modifying
    @Query("""
            update OtpVerification o
               set o.status = com.costonomy.mp.identity.domain.OtpStatus.EXPIRED
             where o.status = com.costonomy.mp.identity.domain.OtpStatus.PENDING
               and o.expiresAt < :now
            """)
    int expireStale(@Param("now") Instant now);
}
