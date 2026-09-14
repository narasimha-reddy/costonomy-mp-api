package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The commercial terms of a SKU at a point in time. Doc 02 §4.
 *
 * <p><b>Never updated in place.</b> A price change closes the current row
 * ({@code effectiveTo} = now, status {@code SUPERSEDED}) and inserts a new one.
 * Doc 02 §4 requires that historical commercial values survive when a transaction
 * references them, and doc 01 §26 wants the price history for pricing
 * intelligence — neither works if a price edit overwrites the old figure.
 *
 * <p>Orders still snapshot their own price (doc 02 §5). This is the catalog's
 * record of what was offered, not the order's record of what was agreed.
 */
@Entity
@Table(name = "supplier_offer")
@Getter
@Setter
@NoArgsConstructor
public class SupplierOffer extends BaseEntity {

    @Column(name = "supplier_sku_id", nullable = false)
    private Long supplierSkuId;

    /** Denormalised from the SKU so ranking can filter by store without a join. */
    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    @Column(name = "canonical_product_id", nullable = false)
    private Long canonicalProductId;

    /** DECIMAL(19,4). Never a double — doc 02 §1. */
    @Column(name = "selling_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal sellingPrice;

    @Column(name = "gst_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal gstRate;

    /** AVAILABLE or OUT_OF_STOCK. Doc 01 §7: nothing else, initially. */
    @Column(name = "availability", nullable = false, length = 32)
    private String availability = Availability.AVAILABLE;

    /** Null means "available, quantity not tracked" — the common case. */
    @Column(name = "available_quantity", precision = 19, scale = 4)
    private BigDecimal availableQuantity;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom = Instant.now();

    /** Null on the current offer. */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** ACTIVE (current) or SUPERSEDED (historical). */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_by")
    private Long createdBy;

    public boolean isPurchasable() {
        return "ACTIVE".equals(status) && Availability.AVAILABLE.equals(availability);
    }

    /** Availability values. Doc 01 §7 allows exactly these two, initially. */
    public static final class Availability {
        private Availability() {
        }

        public static final String AVAILABLE = "AVAILABLE";
        public static final String OUT_OF_STOCK = "OUT_OF_STOCK";

        public static boolean isValid(String value) {
            return AVAILABLE.equals(value) || OUT_OF_STOCK.equals(value);
        }
    }
}
