package com.costonomy.mp.wallet.invoice.costapi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Development and test lists, matching the fake reader's bill (D-114). Never calls anything. The fake reader is
 * refused under a production profile, and this goes with it.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.invoices.reader.provider", havingValue = "FAKE", matchIfMissing = true)
public class FakeCostCatalog implements CostCatalog {

    @Override
    public List<SupplierOption> suppliers(long costOutletId) {
        return List.of(new SupplierOption(2001L, "Kosta Delights - Sea Food"),
                new SupplierOption(2002L, "Ganesh Vegetables"),
                new SupplierOption(2003L, "Sri Lakshmi Dairy"));
    }

    @Override
    public List<SkuOption> skus(long costOutletId) {
        return List.of(sku(9465L, "Prawns 16/20", "KG", "360", "Seafood"),
                sku(152L, "PRAWNS 21/25", "KG", "300", "Seafood"),
                sku(9001L, "Prawns 30/40", "KG", "270", "Seafood"),
                sku(9002L, "Prawns 30/50", "KG", "250", "Seafood"),
                sku(9100L, "Onion", "KG", "40", "Vegetables"),
                sku(9200L, "Milk", "LTR", "60", "Dairy"));
    }

    private static SkuOption sku(Long id, String name, String unit, String price, String category) {
        return new SkuOption(id, name, unit, new BigDecimal(price), category);
    }
}
