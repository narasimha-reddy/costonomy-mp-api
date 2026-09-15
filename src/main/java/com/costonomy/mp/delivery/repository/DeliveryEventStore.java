package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a provider event in its own transaction, before anything acts on it.
 *
 * <p>The same rule as {@code PaymentWebhookStore}, for the same two reasons
 * (D-016, D-021). The event must survive processing that fails, or there is
 * nothing to replay. And {@code uk_delivery_event_provider} rejects a retry — in
 * MySQL that marks the whole transaction rollback-only, so a duplicate caught in
 * the caller's transaction still fails at commit.
 *
 * <p>The duplicate is <b>thrown out of this method rather than caught inside it</b>,
 * because a catch cannot un-doom a transaction that is already rolling back. The
 * caller catches it, once this transaction has finished.
 */
@Service
@RequiredArgsConstructor
public class DeliveryEventStore {

    private final DeliveryEventRepository events;

    /**
     * @throws DataIntegrityViolationException if this provider event is already stored
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryEvent record(DeliveryEvent event) {
        return events.saveAndFlush(event);
    }
}
