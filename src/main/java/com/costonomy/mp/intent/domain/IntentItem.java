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

    /**
     * The offer this line is priced from, and the price itself.
     *
     * <p>Set when the line is added — as the baseline for spotting a change —
     * and re-confirmed when the request is sent, after which it is the price the
     * supplier's reply confirms or declines. From that moment it does not move:
     * the whole point of showing a real figure in the basket is that it is the
     * one that ends up on the order.
     */
    @Column(name = "supplier_offer_id")
    private Long supplierOfferId;

    @Column(name = "unit_price_snapshot", precision = 19, scale = 4)
    private BigDecimal unitPriceSnapshot;

    @Column(name = "gst_rate_snapshot", precision = 9, scale = 4)
    private BigDecimal gstRateSnapshot;

    @Column(name = "notes", length = 500)
    private String notes;

    // No per-line status. The column existed from V23, was never written by
    // anything, and shipped a constant 'REQUESTED' to every client. A line's
    // answer is its offered quantity and the IntentFulfilment derived from it;
    // a status here would be a second, staler copy of that. Dropped in V29.
}
