package com.costonomy.mp.notification.repository;

import com.costonomy.mp.notification.domain.NotificationDelivery;
import com.costonomy.mp.notification.domain.NotificationDeliveryStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    /** Due attempts, oldest first. The dispatcher's only query. */
    @Query("""
            select d from NotificationDelivery d
             where d.status in :statuses
               and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)
             order by d.id asc
            """)
    List<NotificationDelivery> findDue(
            @Param("statuses") List<NotificationDeliveryStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable);

    List<NotificationDelivery> findByNotificationIdOrderByIdAsc(Long notificationId);
}
