package com.costonomy.mp.settlement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What rate applies, to whom, from when. Doc 09 §10–11.
 *
 * <p>Scoped so a negotiated rate for one supplier needs no code change: the most
 * specific ACTIVE row wins — store, then organisation, then the platform default.
 */
@Entity
@Table(name = "commission_configuration")
@Getter
@Setter
@NoArgsConstructor
public class CommissionConfiguration extends BaseEntity {

    /** PLATFORM, SUPPLIER or SUPPLIER_STORE. */
    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    /** Percent: 1.00 is doc 01 §16's default of 1%. */
    @Column(name = "rate_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal ratePercent;

    @Column(name = "config_version", nullable = false)
    private Integer configVersion = 1;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_by")
    private Long createdBy;
}
