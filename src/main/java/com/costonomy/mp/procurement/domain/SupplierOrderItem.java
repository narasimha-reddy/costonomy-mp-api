package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One line of a supplier order.
 *
 * <p>Three quantities, kept apart deliberately: requested, accepted, fulfilled
 * (doc 25's example — ordered 20, accepted 18, received 17). Collapsing any two
 * loses the ability to say whether a shortfall was the supplier declining or the
 * delivery falling short, which is exactly what a dispute turns on.
 */
@Entity
@Table(name = "supplier_order_item")
@Getter
@Setter
@NoArgsConstructor
public class SupplierOrderItem extends BaseEntity {

    @Column(name = "supplier_order_id", nullable = false)
    private Long supplierOrderId;

    /**
     * The cart line this came from, or null when the order came from an intent.
     *
     * <p>Nullable since V24. An intent-built order has no cart behind it; its
     * origin is recorded in {@code intent_order_link} instead.
     */
    @Column(name = "procurement_item_id")
    private Long procurementItemId;

    /** Carried through so an accepted quantity credits back to the need it served. */
    @Column(name = "requirement_item_id")
    private Long requirementItemId;

    @Column(name = "canonical_product_id", nullable = false)
    private Long canonicalProductId;

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    @Column(name = "requested_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedQuantity;

    /**
     * Null until the supplier answers.
     *
     * <p>Null means "not yet answered"; zero means "declined this line". Doc 04 §11
     * requires a zero to be explicit, and a nullable column is what keeps the two
     * distinguishable.
     */
    @Column(name = "accepted_quantity", precision = 19, scale = 4)
    private BigDecimal acceptedQuantity;

    /** What actually arrived. Set at receiving (Phase 12). */
    @Column(name = "fulfilled_quantity", precision = 19, scale = 4)
    private BigDecimal fulfilledQuantity;

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

    /** PENDING, ACCEPTED, PARTIALLY_ACCEPTED or REJECTED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
}
