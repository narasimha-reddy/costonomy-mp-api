package com.costonomy.mp.notification.repository;

import com.costonomy.mp.notification.domain.NotificationCategory;
import com.costonomy.mp.notification.domain.NotificationChannel;
import com.costonomy.mp.notification.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByUserId(Long userId);

    Optional<NotificationPreference> findByUserIdAndCategoryAndChannel(
            Long userId, NotificationCategory category, NotificationChannel channel);
}
