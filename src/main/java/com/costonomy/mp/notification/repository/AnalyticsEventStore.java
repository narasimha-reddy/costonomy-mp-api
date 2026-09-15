package com.costonomy.mp.notification.repository;

import com.costonomy.mp.notification.domain.AnalyticsEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one analytics event, in its own transaction.
 *
 * <p>The D-016 rule, and a batch makes the cost of getting it wrong obvious: a
 * client resends two hundred events, one of which we already have, and the
 * constraint violation marks the ingest transaction rollback-only — so the
 * hundred-and-ninety-nine new ones are lost at commit, and the client is told the
 * whole batch failed. It retries, and the same thing happens forever.
 *
 * <p>Per-event transactions are also simply right for analytics: one malformed row
 * should never discard a batch.
 *
 * @throws DataIntegrityViolationException if the client has already sent this event
 */
@Service
@RequiredArgsConstructor
public class AnalyticsEventStore {

    private final AnalyticsEventRepository events;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalyticsEvent record(AnalyticsEvent event) {
        return events.saveAndFlush(event);
    }
}
