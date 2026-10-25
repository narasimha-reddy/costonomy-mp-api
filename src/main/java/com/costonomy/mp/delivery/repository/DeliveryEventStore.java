package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a provider event <b>in the caller's transaction</b>, so the record and the status change it caused commit or
 * roll back together (D-204 audit, gap 10).
 *
 * <p>It used to be stored in a transaction of its own, before anything acted on it, so that an event survived processing
 * that failed. Nothing replayed it, though: when applying failed, the provider's retry found the stored row, was told
 * {@code DUPLICATE}, and the change was lost for good. Now a failed apply leaves no row and the retry applies it.
 *
 * <p>The replay protection of {@code uk_delivery_event_provider} (doc 06 §13) is kept without a thrown error: the insert
 * is {@code INSERT IGNORE}, so a duplicate is "0 rows" rather than an exception that would mark the caller's transaction
 * rollback-only (D-016, D-021). A concurrent identical event waits on the unique index until the first commits, then
 * is ignored.
 */
@Service
@RequiredArgsConstructor
public class DeliveryEventStore {

    private final DeliveryEventRepository events;

    /** @return true if this provider event was already stored (so nothing was written), false if it is new and stored now */
    @Transactional
    public boolean recordOrDetectDuplicate(DeliveryEvent e) {
        return events.insertUnlessDuplicate(e.getDeliveryId(), e.getEventType(),
                e.getStatus() == null ? null : e.getStatus().name(), e.getProviderCode(), e.getProviderEventId(),
                e.getProviderStatus(), e.getDisposition(), e.getDescription(), e.getPayload(), e.getOccurredAt()) == 0;
    }
}
