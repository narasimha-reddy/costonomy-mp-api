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

/**
 * A person. One row per human, identified by phone number.
 *
 * <p>Deliberately says nothing about <em>what</em> the person is. A single user
 * may be a restaurant's purchase manager, a supplier's salesperson, and an
 * internal operator all at once; those are memberships and role grants recorded
 * elsewhere (V1's {@code user_role}, and the restaurant/supplier membership
 * tables in later migrations). That separation is what lets one phone number
 * sign in and be routed to the right experience by the server, per §23A.30.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    /**
     * E.164, e.g. {@code +919999000001}. Normalised by
     * {@link com.costonomy.mp.identity.service.PhoneNumbers} before any lookup
     * or insert, so one number has exactly one row however it was typed.
     */
    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "name", length = 150)
    private String name;

    @Column(name = "email", length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * When this number last completed OTP verification.
     *
     * <p>Distinct from {@code lastLoginAt}: a user can hold a valid refresh token
     * long after verifying, and some flows (changing a phone number) re-verify
     * without being a login.
     */
    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
