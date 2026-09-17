package com.costonomy.mp.intent.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One of a supplier's packs, and how many of them are wanted.
 *
 * <p>Quantities are <b>packs</b>, as everywhere else a purchase is counted. The
 * canonical product rides alongside the SKU so a clone can be re-shopped against
 * a different supplier without losing what was actually wanted.
 */
@Entity
@Table(name = "intent_item")
@Getter
@Setter
@NoArgsConstructor
public class IntentItem extends BaseEntity {

    @Column(name = "intent_id", nullable = false)
    private Long intentId;

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    @Column(name = "canonical_product_id", nullable = false)
    private Long canonicalProductId;

    @Column(name = "requested_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedQuantity;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "notes", length = 500)
    private String notes;

    /** REQUESTED, then OFFERED, REDUCED or DECLINED once the supplier answers. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "REQUESTED";
}
