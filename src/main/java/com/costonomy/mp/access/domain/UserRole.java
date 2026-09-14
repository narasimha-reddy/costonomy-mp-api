package com.costonomy.mp.access.domain;

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

/**
 * A role granted to a user, within a scope. The authorization source of truth.
 *
 * <p>Membership tables ({@code restaurant_user}, {@code supplier_user}) record
 * employment state — invited, active, removed — and answer "who is in this
 * organisation". They grant nothing. A member with no row here can see nothing,
 * which is deliberate: it makes "invited but not yet given access" a real,
 * representable state rather than an accident.
 */
@Entity
@Table(name = "user_role")
@Getter
@Setter
@NoArgsConstructor
public class UserRole extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "scope_type", nullable = false, length = 32)
    private ScopeType scopeType;

    /** Null for {@link ScopeType#PLATFORM}. */
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
