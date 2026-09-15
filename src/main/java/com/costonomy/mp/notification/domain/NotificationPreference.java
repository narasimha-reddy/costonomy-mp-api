package com.costonomy.mp.notification.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A channel a user has muted for a category. Doc 08 §5.
 *
 * <p><b>Opt-out.</b> A row exists only where something was turned off, so absent
 * means enabled — a new category or a new user starts receiving. The opposite
 * default fails quietly and badly: a restaurant who never learns their order was
 * rejected, and never knew there was a setting.
 */
@Entity
@Table(name = "notification_preference")
@Getter
@Setter
@NoArgsConstructor
public class NotificationPreference extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 32)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "channel", nullable = false, length = 32)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;
}
