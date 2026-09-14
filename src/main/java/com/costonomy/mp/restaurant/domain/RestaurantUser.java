package com.costonomy.mp.restaurant.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Membership in a restaurant organisation — employment state, not capability.
 *
 * <p>What a member may do is decided entirely by {@code user_role}. This record
 * exists so "invited but not yet given access" is a representable state, and so
 * "who belongs to this restaurant" is answerable without inferring it from role
 * grants.
 */
@Entity
@Table(name = "restaurant_user")
@Getter
@Setter
@NoArgsConstructor
public class RestaurantUser extends BaseEntity {

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** INVITED, ACTIVE or REMOVED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "invited_by")
    private Long invitedBy;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "removed_at")
    private Instant removedAt;
}
