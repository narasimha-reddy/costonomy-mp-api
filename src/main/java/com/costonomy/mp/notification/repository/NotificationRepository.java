package com.costonomy.mp.notification.repository;

import com.costonomy.mp.notification.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
            Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /**
     * Mark everything unread as read, in one statement.
     *
     * <p>Not a read-then-save loop: a user with four hundred unread notifications
     * would otherwise load four hundred entities to set one timestamp on each, and
     * anything arriving mid-loop would be marked read without ever being seen.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n set n.readAt = :readAt, n.version = n.version + 1
             where n.userId = :userId and n.readAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") Instant readAt);
}
