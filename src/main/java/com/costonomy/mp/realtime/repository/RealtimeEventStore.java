package com.costonomy.mp.realtime.repository;

import com.costonomy.mp.realtime.domain.RealtimeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects one event onto one channel, in its own transaction.
 *
 * <p>The D-016/D-021 rule again, and it bites harder here than anywhere else. The
 * relay runs <b>inside the outbox drain's transaction</b>, which is publishing a
 * batch of up to a hundred events. A duplicate projection — routine, because the
 * outbox is at-least-once — raises a constraint violation, and in MySQL that marks
 * the enclosing transaction rollback-only. Catching it in the relay changes
 * nothing: the drain's commit then throws, and <b>the entire batch is lost</b>,
 * including events that had nothing to do with the duplicate.
 *
 * <p>So the insert commits on its own, and the duplicate is <b>thrown out of this
 * method rather than caught inside it</b> — a catch cannot un-doom a transaction
 * that is already rolling back. The caller catches it once this transaction has
 * finished.
 *
 * @throws DataIntegrityViolationException if this event is already on this channel
 */
@Service
@RequiredArgsConstructor
public class RealtimeEventStore {

    private final RealtimeEventRepository events;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RealtimeEvent project(RealtimeEvent event) {
        return events.saveAndFlush(event);
    }
}
