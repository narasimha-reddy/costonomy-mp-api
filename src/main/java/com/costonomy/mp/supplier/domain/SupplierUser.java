package com.costonomy.mp.supplier.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Membership in a supplier organisation. Mirrors {@code RestaurantUser}. */
@Entity
@Table(name = "supplier_user")
@Getter
@Setter
@NoArgsConstructor
public class SupplierUser extends BaseEntity {

    @Column(name = "supplier_organization_id", nullable = false)
    private Long supplierOrganizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

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
