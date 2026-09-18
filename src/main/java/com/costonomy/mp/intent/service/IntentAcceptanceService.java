package com.costonomy.mp.intent.service;

import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The supplier's entry points: see the requests, answer one. §10.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. The idempotency claim has to
 * commit in its own transaction before the work runs (see
 * {@code IdempotencyStore}), and the work itself is in {@link IntentResponder} —
 * a separate bean, because a {@code @Transactional} method invoked through
 * {@code this}, including from inside the lambda below, never passes through
 * Spring's proxy and the annotation is silently ignored.
 */
@Service
@RequiredArgsConstructor
public class IntentAcceptanceService {

    /**
     * How many requests the supplier's home carousel shows. §14.
     *
     * <p>A cap rather than a page, because this is a carousel on a home screen
     * and the list screen is where a supplier works through everything. The
     * number is here so the API and the app cannot disagree about it.
     */
    public static final int CAROUSEL_LIMIT = 10;

    /** Anything the supplier can still act on or has just acted on. */
    private static final List<IntentStatus> ACTIONABLE =
            List.of(IntentStatus.OPEN, IntentStatus.RESPONSES_RECEIVED);

    /**
     * Everything a supplier may ever see — which is every status except
     * {@code DRAFT}.
     *
     * <p>Enumerated by exclusion rather than listed, so a status added later is
     * visible by default instead of silently missing from the supplier's history.
     * {@code DRAFT} is the one that must never appear: a basket the restaurant is
     * still filling has not been sent to anybody.
     */
    private static final List<IntentStatus> VISIBLE_TO_SUPPLIER =
            java.util.Arrays.stream(IntentStatus.values())
                    .filter(status -> status != IntentStatus.DRAFT)
                    .toList();

    private final IntentResponder responder;
    private final IdempotencyService idempotency;

    /**
     * Answer a request, at most once per idempotency key.
     *
     * <p>Idempotent because doc 04 §21 requires it of every supplier state
     * transition, and because a duplicate here would write a second acceptance
     * for one request — which {@code uk_intent_acceptance_intent} would reject,
     * turning a retry into a failure the supplier cannot interpret.
     */
    public IntentDtos.IntentResponse respond(
            Long actorId, Long intentId, IntentDtos.RespondRequest request, String idempotencyKey) {

        return idempotency.execute(actorId, "intent.respond", idempotencyKey,
                Map.of("intentId", intentId, "lines", request.lines().size()),
                IntentDtos.IntentResponse.class,
                () -> responder.respond(actorId, intentId, request));
    }

    /** What a reply would come to. Read-only, so no idempotency key. */
    public IntentDtos.RespondPreviewResponse preview(
            Long actorId, Long intentId, IntentDtos.RespondRequest request) {
        return responder.preview(actorId, intentId, request);
    }

    /** The home carousel: the newest requests worth looking at. */
    public List<IntentDtos.IntentResponse> carousel(Long actorId, Long storeId) {
        return responder.forStore(actorId, storeId, ACTIONABLE, CAROUSEL_LIMIT);
    }

    /** The full list, optionally narrowed to one status. */
    public List<IntentDtos.IntentResponse> list(Long actorId, Long storeId, IntentStatus status) {
        if (status == IntentStatus.DRAFT) {
            // Asked for explicitly, refused explicitly — an empty list rather than
            // an error, because there is nothing here to tell a supplier about.
            return List.of();
        }
        var statuses = status == null ? VISIBLE_TO_SUPPLIER : List.of(status);
        return responder.forStore(actorId, storeId, statuses, Integer.MAX_VALUE);
    }
}
