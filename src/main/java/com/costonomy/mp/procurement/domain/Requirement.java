package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** What a restaurant outlet needs. Doc 02 §4. */
@Entity
@Table(name = "requirement")
@Getter
@Setter
@NoArgsConstructor
public class Requirement extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private RequirementStatus status = RequirementStatus.OPEN;

    /** MANUAL or RECOMMENDED — worth distinguishing when measuring recommendations. */
    @Column(name = "source", nullable = false, length = 32)
    private String source = "MANUAL";

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "needed_by")
    private Instant neededBy;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
