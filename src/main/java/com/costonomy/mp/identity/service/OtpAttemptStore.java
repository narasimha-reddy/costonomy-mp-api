package com.costonomy.mp.identity.service;

import com.costonomy.mp.identity.domain.OtpStatus;
import com.costonomy.mp.identity.repository.OtpVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists OTP attempt bookkeeping <b>independently of the request transaction</b>.
 *
 * <p>This is not an organisational split — it is the mechanism, and the reason is
 * worth reading before changing anything here.
 *
 * <p>A failed verification ends by throwing {@code OTP_INVALID}. If the attempt
 * counter were incremented inside the same transaction, that throw would roll the
 * increment back, and the counter would sit at zero forever. The attempt limit
 * would then be decorative: an attacker could submit all one million six-digit
 * codes against a single live challenge and never be stopped. That is exactly
 * what happened in the first version of this code, and what
 * {@code AuthFlowIT.attemptsAreLimited} exists to catch.
 *
 * <p>So the counter is written in its own committed transaction
 * ({@code REQUIRES_NEW}), and — as with {@code IdempotencyStore} — that only works
 * because this is a <b>separate bean</b>. Spring's {@code @Transactional} is
 * proxy-based, so a self-invoked {@code REQUIRES_NEW} method is silently ignored.
 * Do not merge this into {@link OtpService}.
 */
@Service
@RequiredArgsConstructor
public class OtpAttemptStore {

    private final OtpVerificationRepository repository;

    /**
     * Consume one attempt against a challenge.
     *
     * <p>The increment is a single conditional {@code UPDATE} rather than a
     * read-modify-write, so two verifications arriving at once consume two
     * attempts rather than racing and consuming one. The {@code WHERE} clause is
     * what enforces the ceiling; it cannot be exceeded even under concurrency.
     *
     * @return the attempt number just consumed (1-based), or {@code -1} if the
     *         challenge had no attempts left
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int consumeAttempt(Long challengeId) {
        if (repository.incrementAttemptCount(challengeId) == 0) {
            return -1;
        }
        return repository.findById(challengeId)
                .map(o -> o.getAttemptCount())
                .orElse(-1);
    }

    /** Move a challenge to a terminal state, committed regardless of the caller's outcome. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStatus(Long challengeId, OtpStatus status) {
        repository.findById(challengeId).ifPresent(challenge -> {
            challenge.setStatus(status);
            if (status == OtpStatus.VERIFIED) {
                challenge.setVerifiedAt(Instant.now());
            }
            repository.save(challenge);
        });
    }
}
