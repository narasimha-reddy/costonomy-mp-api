package com.costonomy.mp.credit.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A store's standing offer of credit. Doc 01 §18, table from V4.
 *
 * <p>Defaults, not terms. They decide whether a restaurant may ask at all, and
 * seed the numbers the supplier is shown when answering — but an order is always
 * checked against {@link CreditAgreement}, because a supplier may well have agreed
 * something different with this particular restaurant.
 *
 * <p>Lives in the credit module rather than the supplier module: it is a credit
 * rule that happens to be attached to a store, and keeping it here means every
 * rule about credit is in one place.
 */
@Entity
@Table(name = "supplier_credit_policy")
@Getter
@Setter
@NoArgsConstructor
public class SupplierCreditPolicy extends BaseEntity {

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    /** Off unless the supplier turns it on. Absent is never "enabled with defaults". */
    @Column(name = "credit_enabled", nullable = false)
    private Boolean creditEnabled = false;

    @Column(name = "default_credit_limit", precision = 19, scale = 4)
    private BigDecimal defaultCreditLimit;

    @Column(name = "default_credit_period_days")
    private Integer defaultCreditPeriodDays;

    @Column(name = "default_grace_period_days", nullable = false)
    private Integer defaultGracePeriodDays = 0;

    @Column(name = "max_single_order_credit", precision = 19, scale = 4)
    private BigDecimal maxSingleOrderCredit;

    @Column(name = "auto_suspend_enabled", nullable = false)
    private Boolean autoSuspendEnabled = true;

    @Column(name = "max_overdue_amount", precision = 19, scale = 4)
    private BigDecimal maxOverdueAmount;
}
