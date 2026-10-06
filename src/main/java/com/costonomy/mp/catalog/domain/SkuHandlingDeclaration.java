package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A supplier's declaration of how a SKU must be handled (cold chain, catch-weight). Superseded, never edited:
 * the row that is no longer current keeps its dates, who declared it and why (D-134).
 */
@Entity
@Table(name = "supplier_sku_handling_declaration")
@Getter
@Setter
@NoArgsConstructor
public class SkuHandlingDeclaration extends BaseEntity {

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    @Column(name = "requires_cold_chain", nullable = false)
    private boolean requiresColdChain;

    @Column(name = "is_catch_weight", nullable = false)
    private boolean catchWeight;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom = Instant.now();

    /** Null while this is the current declaration. */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "declared_by")
    private Long declaredBy;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "superseded_by_id")
    private Long supersededById;
}
