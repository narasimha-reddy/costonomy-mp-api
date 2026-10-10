package com.costonomy.mp.procurement.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

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

    /**
     * Exact weight dispatched from warehouse/packhouse for catch-weight lines (e.g. 4.82 kg).
     */
    @Column(name = "dispatched_weight", precision = 19, scale = 4)
    private BigDecimal dispatchedWeight;

    /**
     * The quantity the buyer is billed for on a weighed catch-weight line: the scale reading, capped at
     * what was accepted (D-128). Null until weighed. This, not {@code dispatchedWeight}, is the basis for
     * the line's figures and for what receiving checks against.
     */
    @Column(name = "billable_quantity", precision = 19, scale = 4)
    private BigDecimal billableQuantity;

    @Column(name = "weighed_at")
    private Instant weighedAt;

    /**
     * Delta amount (refund or surcharge) due to difference between acceptedQuantity and dispatchedWeight.
     */
    @Column(name = "weight_delta_amount", precision = 19, scale = 4)
    private BigDecimal weightDeltaAmount;

    /**
     * Doorstep verification: quantity accepted by chef at the door.
     */
    @Column(name = "doorstep_accepted_qty", precision = 19, scale = 4)
    private BigDecimal doorstepAcceptedQty;

    /**
     * Doorstep verification: quantity rejected by chef at the door (damaged/spoiled/wrong grade).
     */
    @Column(name = "doorstep_rejected_qty", precision = 19, scale = 4)
    private BigDecimal doorstepRejectedQty;

    @Column(name = "doorstep_rejection_reason", length = 64)
    private String doorstepRejectionReason;

    /**
     * Credit note / refund amount automatically generated for doorstep-rejected quantity.
     */
    @Column(name = "doorstep_refund_amount", precision = 19, scale = 4)
    private BigDecimal doorstepRefundAmount;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "hsn_code", length = 16)
    private String hsnCode;

    /**
     * Whether this line item is perishable and requires cold chain transport.
     */
    @Column(name = "requires_cold_chain", nullable = false)
    private boolean requiresColdChain = false;

    /**
     * Whether this line item is sold on a catch-weight basis (natural pack variance).
     */
    @Column(name = "is_catch_weight", nullable = false)
    private boolean isCatchWeight = false;

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

    /**
     * This line's answer. D-091 typed it.
     *
     * <p>The enum has existed since doc 04 §11; the field was a {@code String}
     * and every write went through {@code OrderItemStatus.X.name()}, which made
     * the writes safe by convention and left every read comparing against a
     * literal nothing checked.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private OrderItemStatus status = OrderItemStatus.PENDING;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
}
