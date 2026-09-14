package com.costonomy.mp.identity.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A registered app install, and its push target. Doc 02 §3. */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
public class Device extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "platform", nullable = false, length = 16)
    private DevicePlatform platform;

    /**
     * FCM/APNs token. Identifies one app install, not one device.
     *
     * <p>If the same token reappears under a different user — a shared handset,
     * or a reinstall — the existing row is reassigned rather than duplicated.
     * Otherwise the previous user keeps receiving this user's notifications,
     * which for a procurement app means leaking order values between businesses.
     */
    @Column(name = "push_token", length = 512)
    private String pushToken;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "device_model", length = 120)
    private String deviceModel;

    @Column(name = "os_version", length = 32)
    private String osVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private DeviceStatus status = DeviceStatus.ACTIVE;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}
