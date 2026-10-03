package com.costonomy.mp.wallet.invoice.costapi;

import java.math.BigDecimal;
import java.util.List;

/**
 * The cost app's supplier and SKU lists for one cost-app outlet, read only (D-114). Used by the review screen's
 * pickers. Only the fields named here cross over. The cost outlet is always the one the caller's marketplace outlet
 * is mapped to ({@link CostOutletMap}, D-115), never another.
 */
public interface CostCatalog {

    /** The cost outlet's suppliers that are not disabled. */
    List<SupplierOption> suppliers(long costOutletId);

    /** The cost outlet's SKUs that are not disabled. */
    List<SkuOption> skus(long costOutletId);

    record SupplierOption(Long id, String name) {
    }

    /**
     * {@code unitPrice}: the SKU's current price per unit (the cost app's {@code itemPrice}, else
     * {@code initialItemPrice}), at most 4 decimals.
     */
    record SkuOption(Long id, String name, String unit, BigDecimal unitPrice, String categoryName) {
    }
}
