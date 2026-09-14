package com.costonomy.mp.identity.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.domain.OtpPurpose;
import com.costonomy.mp.identity.domain.OtpStatus;
import com.costonomy.mp.identity.domain.OtpVerification;
import com.costonomy.mp.identity.provider.OtpDeliveryException;
import com.costonomy.mp.identity.provider.OtpProvider;
import com.costonomy.mp.identity.repository.OtpVerificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Issues and verifies one-time codes. Doc 09 §1.
 *
 * <p>Implements the four controls the spec requires — expiry, attempt limit,
 * resend cooldown, request throttling — and one it does not name but that the
 * others depend on: <b>superseding</b>. Issuing a new code retires the previous
 * one, so a phone with several outstanding codes cannot be used to multiply the
 * attempt budget.
 *
 * <p>Codes are hashed with BCrypt. See {@link OtpVerification} for why a fast
 * hash would be inadequate for a six-digit secret.
 *
 * <p>Throttling counts rows in {@code otp_verification} rather than using Redis.
 * The volume is low, the data is already there, and it keeps local development
 * working with no Redis instance (doc 10 §6). Move it to Redis if request rates
 * ever make the count expensive.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpVerificationRepository repository;
    private final OtpAttemptStore attemptStore;
    private final OtpProvider otpProvider;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${costonomy.mp.otp.length:6}")
    private int otpLength;

    @Value("${costonomy.mp.otp.ttl:5m}")
    private Duration ttl;

    @Value("${costonomy.mp.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${costonomy.mp.otp.resend-cooldown:60s}")
    private Duration resendCooldown;

    @Value("${costonomy.mp.otp.max-requests-per-hour:10}")
    private int maxRequestsPerHour;

    /**
     * A fixed code for local and test profiles only.
     *
     * <p>Unset in every other profile, and {@link #generateCode()} falls back to a
     * random code when it is blank — so a misconfigured deployment produces an
     * unguessable code rather than {@code 123456}.
     */
    @Value("${costonomy.mp.otp.mock-code:}")
    private String mockCode;

    /** What the caller needs to render the OTP screen (§23A.7). */
    public record Challenge(Instant expiresAt, Duration resendAfter, int maxAttempts) {
    }

    @Transactional
    public Challenge request(String normalizedPhone, OtpPurpose purpose) {
        Instant now = Instant.now();

        enforceHourlyLimit(normalizedPhone, now);
        enforceResendCooldown(normalizedPhone, purpose, now);
        supersedePending(normalizedPhone, purpose);

        String code = generateCode();

        var challenge = new OtpVerification();
        challenge.setPhone(normalizedPhone);
        challenge.setPurpose(purpose);
        challenge.setOtpHash(encoder.encode(code));
        challenge.setStatus(OtpStatus.PENDING);
        challenge.setAttemptCount(0);
        challenge.setMaxAttempts(maxAttempts);
        challenge.setExpiresAt(now.plus(ttl));
        challenge.setProvider(otpProvider.name());

        // Persisted before sending, so a provider timeout that actually delivered
        // the SMS still leaves a verifiable challenge. The reverse order would
        // hand the user a code we have no record of.
        repository.saveAndFlush(challenge);

        try {
            var result = otpProvider.send(normalizedPhone, code);
            challenge.setProviderReference(result.providerReference());
            repository.save(challenge);
        } catch (OtpDeliveryException ex) {
            log.error("OTP delivery failed for {}: {}",
                    PhoneNumbers.mask(normalizedPhone), ex.getMessage());
            // Surfaces as the "provider failure" state in §23A.6. The challenge
            // row stays — see above.
            throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "We couldn't send the code just now. Please try again.");
        }

        return new Challenge(challenge.getExpiresAt(), resendCooldown, maxAttempts);
    }

    /**
     * Verify a submitted code.
     *
     * <p>All bookkeeping goes through {@link OtpAttemptStore}, which commits in its
     * own transaction. That is essential rather than stylistic: this method ends by
     * throwing on every failure path, and a counter incremented in the caller's
     * transaction would be rolled back by that throw — leaving the attempt limit
     * unenforced and a six-digit code open to exhaustive guessing.
     *
     * @throws BusinessException OTP_EXPIRED, OTP_ATTEMPTS_EXCEEDED or OTP_INVALID
     */
    @Transactional
    public void verify(String normalizedPhone, OtpPurpose purpose, String submittedCode) {
        Instant now = Instant.now();

        OtpVerification challenge = repository
                .findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(
                        normalizedPhone, purpose, OtpStatus.PENDING)
                // No live challenge. Deliberately reported as expired rather than
                // "no code was requested": the latter confirms to an attacker
                // whether a number is mid-login.
                .orElseThrow(() -> new BusinessException(ErrorCode.OTP_EXPIRED));

        if (challenge.isExpired(now)) {
            attemptStore.markStatus(challenge.getId(), OtpStatus.EXPIRED);
            throw new BusinessException(ErrorCode.OTP_EXPIRED);
        }

        // Consumed before the code is compared, so a crash or a rollback part-way
        // through verification cannot buy a free retry.
        int attempt = attemptStore.consumeAttempt(challenge.getId());
        if (attempt < 0) {
            attemptStore.markStatus(challenge.getId(), OtpStatus.ATTEMPTS_EXCEEDED);
            throw new BusinessException(ErrorCode.OTP_ATTEMPTS_EXCEEDED);
        }

        if (!encoder.matches(submittedCode, challenge.getOtpHash())) {
            if (attempt >= challenge.getMaxAttempts()) {
                // That was the last one. Terminal: even the correct code will not
                // work now, so the limit stops an attacker rather than slowing them.
                attemptStore.markStatus(challenge.getId(), OtpStatus.ATTEMPTS_EXCEEDED);
                throw new BusinessException(ErrorCode.OTP_ATTEMPTS_EXCEEDED);
            }
            // Tells the client how many tries remain, which §23A.7 shows, without
            // revealing anything about the code itself.
            throw new BusinessException(ErrorCode.OTP_INVALID,
                    ErrorCode.OTP_INVALID.defaultMessage(),
                    Map.of("attemptsRemaining", challenge.getMaxAttempts() - attempt));
        }

        // Marked in the caller's transaction, not the store's: if the login that
        // follows fails, the code should stay usable for a genuine retry.
        challenge.setStatus(OtpStatus.VERIFIED);
        challenge.setVerifiedAt(now);
        repository.save(challenge);
    }

    private void enforceHourlyLimit(String phone, Instant now) {
        long recent = repository.countByPhoneAndCreatedAtAfter(phone, now.minus(Duration.ofHours(1)));
        if (recent >= maxRequestsPerHour) {
            log.warn("OTP hourly limit reached for {}", PhoneNumbers.mask(phone));
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many code requests. Please try again later.");
        }
    }

    private void enforceResendCooldown(String phone, OtpPurpose purpose, Instant now) {
        repository.findFirstByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose)
                .ifPresent(previous -> {
                    Instant allowedAt = previous.getCreatedAt().plus(resendCooldown);
                    if (allowedAt.isAfter(now)) {
                        long secondsLeft = Duration.between(now, allowedAt).toSeconds();
                        throw new BusinessException(ErrorCode.OTP_RESEND_TOO_SOON,
                                ErrorCode.OTP_RESEND_TOO_SOON.defaultMessage(),
                                // §23A.7 shows a resend countdown; it needs this.
                                Map.of("retryAfterSeconds", Math.max(1, secondsLeft)));
                    }
                });
    }

    /**
     * Retire outstanding codes for this phone and purpose.
     *
     * <p>Without this, requesting five codes would leave five live challenges,
     * each with its own attempt budget — five times the guesses against the same
     * six-digit space.
     */
    private void supersedePending(String phone, OtpPurpose purpose) {
        var pending = repository.findByPhoneAndPurposeAndStatus(phone, purpose, OtpStatus.PENDING);
        pending.forEach(o -> o.setStatus(OtpStatus.SUPERSEDED));
        repository.saveAll(pending);
    }

    private String generateCode() {
        if (mockCode != null && !mockCode.isBlank()) {
            return mockCode;
        }
        int bound = (int) Math.pow(10, otpLength);
        // SecureRandom, not Random: a predictable code is no code at all.
        return String.format("%0" + otpLength + "d", RANDOM.nextInt(bound));
    }
}
