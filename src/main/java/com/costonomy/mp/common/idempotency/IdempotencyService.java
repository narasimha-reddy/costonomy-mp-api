package com.costonomy.mp.common.idempotency;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a mutating operation safe to retry. Doc 04 §21.
 *
 * <p>The protocol, for a given (actor, operation, key):
 *
 * <ol>
 *   <li><b>Claim.</b> {@link IdempotencyStore#claim} inserts an
 *       {@code IN_PROGRESS} row in its own committed transaction. The unique
 *       constraint decides the race — exactly one concurrent caller wins.</li>
 *   <li><b>The loser reads the winner's row</b> and responds:
 *       <ul>
 *         <li>{@code COMPLETED}, hash matches → replay the stored response.</li>
 *         <li>{@code IN_PROGRESS} → {@code IDEMPOTENT_REQUEST_IN_PROGRESS}; retry later.</li>
 *         <li>hash differs → {@code IDEMPOTENCY_KEY_REUSE}.</li>
 *       </ul></li>
 *   <li><b>The winner runs the operation</b>, then stores the response, or
 *       releases the key on failure so a genuine retry can run.</li>
 * </ol>
 *
 * <p><b>This prevents duplicate work; it does not make the work itself
 * concurrency-safe.</b> Supplier acceptance racing its timeout is still settled
 * by optimistic locking on the order aggregate (doc 03 §5), and has to be: those
 * two arrive with different keys — one from a supplier's request, one from a
 * scheduled job — so no idempotency key relates them.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    @Value("${costonomy.mp.idempotency.retention:24h}")
    private Duration retention;

    /**
     * Run {@code action} at most once for this (actor, operation, key).
     *
     * @param responseType the type the stored response replays as; must be the
     *                     same type {@code action} returns
     */
    public <T> T execute(
            Long actorId,
            String operation,
            String idempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            Supplier<T> action) {

        String requestHash = hash(requestPayload);

        Optional<IdempotencyRecord> existing =
                store.claim(actorId, operation, idempotencyKey, requestHash, retention);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, responseType);
        }

        try {
            T result = action.get();
            store.complete(actorId, operation, idempotencyKey, result);
            return result;
        } catch (RuntimeException ex) {
            store.fail(actorId, operation, idempotencyKey);
            throw ex;
        }
    }

    private <T> T replay(IdempotencyRecord record, String requestHash, Class<T> responseType) {
        if (!record.getRequestHash().equals(requestHash)) {
            // Same key, different request. Replaying the first response would tell
            // the client their second, different order had succeeded.
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }

        return switch (record.getState()) {
            case COMPLETED -> deserialize(record, responseType);
            case IN_PROGRESS -> throw new BusinessException(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS);
            case FAILED -> throw new BusinessException(
                    ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS,
                    "The previous attempt failed. Please retry.");
        };
    }

    private <T> T deserialize(IdempotencyRecord record, Class<T> responseType) {
        if (record.getResponseBody() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        try {
            return objectMapper.readValue(record.getResponseBody(), responseType);
        } catch (Exception ex) {
            // The stored shape no longer matches the class — typically a DTO that
            // changed between the original call and the replay, across a deploy.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * SHA-256 over the canonical JSON of the request.
     *
     * <p>Computed from our deserialised DTO rather than the client's raw bytes,
     * so cosmetic differences in the client's JSON (key order, whitespace) do not
     * read as a different payload.
     */
    String hash(Object payload) {
        try {
            String canonical = payload == null ? "" : objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }
    }
}
