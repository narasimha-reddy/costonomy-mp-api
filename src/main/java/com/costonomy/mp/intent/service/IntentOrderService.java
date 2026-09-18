package com.costonomy.mp.intent.service;

import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.intent.web.dto.IntentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * The restaurant's order endpoints: see the figure, then commit to it. §13.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}, for the reason documented on
 * {@link IntentOrderCreator}: the idempotency claim must commit in its own
 * transaction before the work runs, and a {@code @Transactional} method invoked
 * from inside the lambda below would never pass through Spring's proxy.
 */
@Service
@RequiredArgsConstructor
public class IntentOrderService {

    private final IntentOrderCreator creator;
    private final IdempotencyService idempotency;

    /** What ordering this right now would cost. Changes nothing. */
    public IntentDtos.OrderPreviewResponse preview(
            Long actorId, Long intentId, IntentDtos.CreateOrderRequest request) {
        return creator.preview(actorId, intentId, request);
    }

    /**
     * Create the order, at most once per idempotency key.
     *
     * <p>The most important idempotent endpoint in the system: this is where money
     * is committed, and a duplicate would place — and charge for — two real
     * orders. Three guards, deliberately layered, because each catches what the
     * others miss:
     *
     * <ol>
     *   <li>the {@code Idempotency-Key}, which catches a retry that carries it;</li>
     *   <li>{@code uk_intent_order_link_intent}, which catches a retry that does
     *       not — a client crashing and retrying often generates a fresh key, and
     *       the database constraint does not care what the header said;</li>
     *   <li>the early return in {@link IntentOrderCreator#create}, which turns
     *       that constraint from an error into the answer the caller wanted.</li>
     * </ol>
     *
     * <p>The payload fingerprint includes the quantities, so replaying a key with
     * different quantities is reported as {@code IDEMPOTENCY_KEY_REUSE} rather
     * than silently returning an order for amounts nobody asked for.
     */
    public IntentDtos.CreateOrderResponse create(
            Long actorId, Long intentId, IntentDtos.CreateOrderRequest request,
            String idempotencyKey) {

        Map<String, Object> fingerprint = new HashMap<>();
        fingerprint.put("intentId", intentId);
        fingerprint.put("paymentMethod", request == null ? null : request.paymentMethod());
        if (request != null && request.lines() != null) {
            fingerprint.put("lines", request.lines().stream()
                    .map(line -> "%d:%s".formatted(
                            line.intentItemId(), line.quantity().stripTrailingZeros().toPlainString()))
                    .sorted()
                    .toList());
        }

        return idempotency.execute(actorId, "intent.createOrder", idempotencyKey,
                fingerprint, IntentDtos.CreateOrderResponse.class,
                () -> creator.create(actorId, intentId, request));
    }
}
