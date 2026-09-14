package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A cart line: this much of this product, from this supplier, at this price.
 *
 * <p>The price fields are a <b>snapshot</b>, taken when the line was added or last
 * revalidated. That is what a price change is detected against — compare the
 * snapshot to the live offer and a difference is a fact to show the restaurant,
 * not a number to overwrite (§23A.16, guardrail 13).
 */
@Entity
@Table(name = "procurement_item")
@Getter
@Setter
@NoArgsConstructor
public class ProcurementItem extends BaseEntity {

    @Column(name = "procurement_id", nullable = false)
    private Long procurementId;

    /** Ties this line back to the need it serves, so a shortfall can be credited back. */
    @Column(name = "requirement_item_id")
    private Long requirementItemId;

    @Column(name = "canonical_product_id", nullable = false)
    private Long canonicalProductId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    /** The exact offer row this price came from, so a change is traceable. */
    @Column(name = "supplier_offer_id", nullable = false)
    private Long supplierOfferId;

    @Column(name = "requested_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedQuantity;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "unit_price_snapshot", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceSnapshot;

    @Column(name = "gst_rate_snapshot", nullable = false, precision = 9, scale = 4)
    private BigDecimal gstRateSnapshot;

    @Column(name = "line_item_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineItemValue = BigDecimal.ZERO;

    @Column(name = "line_gst", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineGst = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    /** ACTIVE or REMOVED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";
}
