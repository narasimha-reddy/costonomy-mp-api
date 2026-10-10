package com.costonomy.mp.common.idempotency;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 *         <li>{@code FAILED} → {@code IDEMPOTENT_PREVIOUS_ATTEMPT_FAILED}; use a new key.</li>
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
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    /**
     * A copy of the app mapper used ONLY to hash. Map entries and bean properties are written in sorted order, so the
     * hash of one logical request is the same whichever map implementation or JVM built it ({@code Map.of} iterates in
     * an order salted per JVM: a retry after a restart, or on another instance, would otherwise read as a different
     * request and be refused as a key reuse). The stored responses still use {@link #objectMapper}.
     */
    private final ObjectMapper canonicalMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

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
            if (ex instanceof BusinessException refusal && refusal.code() == ErrorCode.WITHDRAWALS_PAUSED) {
                // Refused before anything was done, for a reason that will pass: a FAILED key would answer the
                // app's retry with "the previous attempt failed" for as long as it kept the key, which a client
                // keeps for a server error, and the pause would outlast itself.
                store.release(actorId, operation, idempotencyKey);
            } else {
                store.fail(actorId, operation, idempotencyKey);
            }
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
            // Definitive: this key will never run again, so the client must use a NEW key. Not "in progress":
            // a client keeps its key on that code and would loop on it for the whole retention.
            case FAILED -> throw new BusinessException(ErrorCode.IDEMPOTENT_PREVIOUS_ATTEMPT_FAILED);
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
     * read as a different payload. Map entries and properties are sorted (see
     * {@link #canonicalMapper}); numbers are the caller's to normalise (the money
     * payloads write the amount as scaled plain text).
     */
    String hash(Object payload) {
        try {
            String canonical = payload == null ? "" : canonicalMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }
    }
}
