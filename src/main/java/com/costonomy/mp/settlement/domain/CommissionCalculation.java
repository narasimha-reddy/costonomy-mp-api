package com.costonomy.mp.settlement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one supplier order owes. Doc 01 §16.
 *
 * <p>Written once and never recomputed. The rate is <b>copied</b> onto the row
 * rather than looked up when read, because doc 05 §33 requires a historical
 * settlement not to depend on current configuration — recomputing would make every
 * past figure a function of today's table, and a statement printed twice would
 * disagree with itself.
 */
@Entity
@Table(name = "commission_calculation")
@Getter
@Setter
@NoArgsConstructor
public class CommissionCalculation extends BaseEntity {

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "supplier_organization_id", nullable = false)
    private Long supplierOrganizationId;

    /** Accepted item value plus GST, excluding delivery. Doc 01 §16. */
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    /** The rate at the moment of calculation. Never re-read. */
    @Column(name = "rate_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal ratePercent;

    @Column(name = "commission_configuration_id")
    private Long commissionConfigurationId;

    @Column(name = "commission_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal commissionAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    /** Null until swept into a settlement. */
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;
}
