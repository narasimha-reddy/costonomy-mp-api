package com.costonomy.mp.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The transactional half of idempotency, deliberately a <b>separate bean</b> from
 * {@link IdempotencyService}.
 *
 * <p>This split is not organisational tidiness — it is what makes the mechanism
 * work. Spring's {@code @Transactional} is implemented with a proxy, and a
 * self-invocation ({@code this.claim(...)}) does not pass through that proxy, so
 * the annotation is silently ignored. Had these methods lived on
 * {@code IdempotencyService} and been called from its own {@code execute()},
 * {@code REQUIRES_NEW} would never have taken effect: the claim would have
 * joined the caller's transaction and stayed invisible to concurrent duplicates
 * until that transaction committed — which is exactly when it is too late,
 * because by then both callers have already authorised a payment.
 *
 * <p>Every method here is {@code REQUIRES_NEW} and commits independently of
 * whatever transaction the business operation is running in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyStore {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Claim the key by inserting an {@code IN_PROGRESS} row.
     *
     * @return empty if this caller won the claim and should run the operation;
     *         the existing record if another caller already owns it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> claim(
            Long actorId, String operation, String key, String requestHash, Duration retention) {

        // An optimisation for the common case. The unique constraint below is
        // the actual guard.
        Optional<IdempotencyRecord> existing =
                repository.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key);
        if (existing.isPresent()) {
            return existing;
        }

        var record = new IdempotencyRecord();
        record.setActorId(actorId);
        record.setOperation(operation);
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setState(IdempotencyRecord.State.IN_PROGRESS);
        record.setExpiresAt(Instant.now().plus(retention));

        try {
            repository.saveAndFlush(record);
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            // Another thread inserted between the check and the insert. Expected
            // under concurrency — that caller owns the operation.
            log.debug("Idempotency claim lost: operation={} key={}", operation, key);
            return Optional.of(repository
                    .findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key)
                    .orElseThrow(() -> ex));
        }
    }

    /** Store the response so a later retry replays it instead of re-running. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long actorId, String operation, String key, Object response) {
        repository.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key)
                .ifPresent(record -> {
                    try {
                        record.setResponseBody(objectMapper.writeValueAsString(response));
                        record.setResponseStatus(200);
                        record.setState(IdempotencyRecord.State.COMPLETED);
                        repository.save(record);
                    } catch (Exception ex) {
                        // The operation succeeded, so this must not fail the request.
                        // The consequence is that a retry re-runs rather than
                        // replaying — which the operation's own concurrency guards
                        // still protect against.
                        log.error("Could not store idempotent response: operation={} key={}",
                                operation, key, ex);
                    }
                });
    }

    /**
     * Release the key after a failure so a genuine retry can run.
     *
     * <p>Leaving it {@code IN_PROGRESS} would block the client until the record
     * expired; marking it {@code COMPLETED} would replay the failure forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long actorId, String operation, String key) {
        repository.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key)
                .ifPresent(record -> {
                    record.setState(IdempotencyRecord.State.FAILED);
                    repository.save(record);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired(Instant now) {
        return repository.deleteExpired(now);
    }
}
