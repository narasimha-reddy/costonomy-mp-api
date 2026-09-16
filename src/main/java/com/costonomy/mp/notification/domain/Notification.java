package com.costonomy.mp.notification.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One thing one user is told. Doc 08 §4, §23A.27.
 *
 * <p>The in-app record, and the origin of any push or SMS that follows it. Unread
 * state lives here because §23A.27 requires it to be server-backed — a badge count
 * computed on a phone disagrees with the same user's other phone.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 32)
    private NotificationCategory category;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** The outbox event this came from. With the user, the deduplication key. */
    @Column(name = "event_id", columnDefinition = "char(36)")
    private String eventId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    /** What tapping it opens. A route the app resolves, not a URL we built. */
    @Column(name = "target_type", length = 64)
    private String targetType;

    /**
     * Which side of the trade was told: {@code OUTLET} or {@code SUPPLIER_STORE}.
     *
     * <p>Stored, not derived. {@code SupplierOrderExpired} has a rule for each
     * side, so the event type alone cannot say which one wrote this row — and the
     * client cannot work it out either, because someone who is both a supplier and
     * a restaurant has no single role to route by.
     */
    @Column(name = "audience", length = 32)
    private String audience;

    @Column(name = "target_id")
    private Long targetId;

    /** Doc 08 §5: critical notifications ignore preferences. */
    @Column(name = "critical", nullable = false)
    private Boolean critical = false;

    @Column(name = "read_at")
    private Instant readAt;
}
