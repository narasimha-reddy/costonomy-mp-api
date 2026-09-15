package com.costonomy.mp.notification.repository;

import com.costonomy.mp.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one notification, in its own transaction.
 *
 * <p>The D-016/D-021/D-031 rule, for the fourth time and for the same reason the
 * realtime relay needed it: this runs inside the outbox drain's transaction, which
 * is publishing a batch of up to a hundred events. A duplicate — routine, because
 * the outbox is at-least-once — raises a constraint violation that marks the
 * enclosing transaction rollback-only, and the whole batch would be lost at
 * commit. It also fans out to many users, so without a separate transaction one
 * duplicate recipient would discard the notifications of everyone else on the same
 * event.
 *
 * <p>The duplicate is thrown rather than caught here: a catch cannot un-doom a
 * transaction that is already rolling back.
 *
 * @throws DataIntegrityViolationException if this user already has this event
 */
@Service
@RequiredArgsConstructor
public class NotificationStore {

    private final NotificationRepository notifications;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification create(Notification notification) {
        return notifications.saveAndFlush(notification);
    }
}
