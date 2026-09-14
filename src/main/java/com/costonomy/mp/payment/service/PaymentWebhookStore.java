package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.domain.PaymentWebhookEvent;
import com.costonomy.mp.payment.repository.PaymentWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of webhook handling, deliberately a <b>separate bean</b>
 * from {@link PaymentWebhookService} — the same rule as {@code IdempotencyStore}
 * and {@code OtpAttemptStore}, and for the same reason (docs/DECISIONS.md D-016).
 *
 * <p>Two things force the split, and the second one produced a real bug.
 *
 * <p><b>The record must outlive its own processing.</b> "Persist before acting"
 * only means something if the persisted row survives an action that fails. Inside
 * the caller's transaction it would not: a failure that rolls that transaction
 * back takes the provider's statement with it, leaving nothing to replay.
 *
 * <p><b>A duplicate must not poison the caller's transaction.</b> Providers retry,
 * so {@code uk_webhook_provider_event} rejects the second insert — and in MySQL a
 * constraint violation marks the whole transaction rollback-only. Catching the
 * exception then looks like it worked, and the commit throws
 * {@code UnexpectedRollbackException} afterwards: the endpoint returned <b>500</b>
 * for a duplicate, which tells the provider to retry an event we already have,
 * forever. Insert here, in a transaction of its own, and only that inner
 * transaction is doomed.
 */
@Service
@RequiredArgsConstructor
public class PaymentWebhookStore {

    private final PaymentWebhookEventRepository events;

    /**
     * Store the event, committing immediately.
     *
     * <p>The unique constraint is the arbiter rather than a preceding
     * {@code findBy...} check, which would race with the very retry it is meant
     * to catch: two concurrent deliveries of one event both see no row and both
     * process it.
     *
     * <p><b>The duplicate is thrown, not swallowed here.</b> Catching it inside
     * this method would not help: the transaction is already marked rollback-only
     * by then, so the commit this method returns into throws
     * {@code UnexpectedRollbackException} anyway — which is precisely how the
     * duplicate turned into a 500 the first time. The caller catches it, after
     * this transaction has finished rolling back.
     *
     * @throws DataIntegrityViolationException if the provider has sent this event before
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentWebhookEvent record(PaymentWebhookEvent event) {
        return events.saveAndFlush(event);
    }

    /** Write back the outcome of processing, whatever happened to the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(PaymentWebhookEvent event) {
        events.save(event);
    }
}
