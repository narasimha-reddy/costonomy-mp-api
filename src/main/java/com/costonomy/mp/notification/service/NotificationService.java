package com.costonomy.mp.notification.service;

import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.notification.domain.Notification;
import com.costonomy.mp.notification.repository.NotificationRepository;
import com.costonomy.mp.notification.web.dto.NotificationDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The inbox. Doc 04 §18, §23A.27.
 *
 * <p><b>A notification belongs to a user, not to a scope.</b> There is no
 * permission check here and no outlet parameter: you read your own notifications,
 * and the id in the JWT is the whole of the authorization. A `notificationId` from
 * someone else's inbox is not found rather than forbidden (doc 09 §3).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private static final int MAX_PAGE = 100;

    private final NotificationRepository notifications;

    @Transactional(readOnly = true)
    public NotificationDtos.InboxResponse inbox(Long userId, boolean unreadOnly, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_PAGE);
        var page = PageRequest.of(0, size);

        List<Notification> found = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, page)
                : notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, page);

        return new NotificationDtos.InboxResponse(
                // Always the true unread count, not the count of this page —
                // §23A.27's badge is server-backed precisely so it is right.
                notifications.countByUserIdAndReadAtIsNull(userId),
                found.stream().map(this::toResponse).toList());
    }

    @Transactional
    public NotificationDtos.NotificationResponse markRead(Long userId, Long notificationId) {
        var notification = notifications.findById(notificationId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Notification", notificationId));

        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notifications.save(notification);
        }
        return toResponse(notification);
    }

    @Transactional
    public long markAllRead(Long userId) {
        // One statement rather than a loop: a user with four hundred unread would
        // otherwise load four hundred entities, and anything arriving mid-loop
        // would be marked read without ever being seen.
        return notifications.markAllRead(userId, Instant.now());
    }

    private NotificationDtos.NotificationResponse toResponse(Notification notification) {
        return new NotificationDtos.NotificationResponse(
                notification.getId(), notification.getCategory(), notification.getEventType(),
                notification.getTitle(), notification.getBody(),
                notification.getTargetType(), notification.getTargetId(),
                notification.getAudience(),
                Boolean.TRUE.equals(notification.getCritical()),
                notification.getReadAt() != null, notification.getCreatedAt());
    }
}
