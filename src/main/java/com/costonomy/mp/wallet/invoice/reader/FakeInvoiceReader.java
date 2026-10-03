package com.costonomy.mp.wallet.invoice.reader;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Development and test reader: the same answer for every bill, a Hyderabad seafood bill, with the cost app's
 * matches filled in as the real reader would (D-114), so the review screen can be built and tried locally. The
 * ids are the fake lookups' ids ({@code FakeCostCatalog}). Refused under a production profile by
 * {@code ProductionProviderGuard}.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.invoices.reader.provider", havingValue = "FAKE", matchIfMissing = true)
public class FakeInvoiceReader implements InvoiceReader {

    public static final long SUPPLIER_ID = 2001;
    public static final String SUPPLIER_NAME = "Kosta Delights - Sea Food";

    @Override
    public ReadResult read(List<InvoiceFile> pages, ReadContext ctx) {
        return ReadResult.of(new InvoiceReading("KOSTA Delights", null, "1631", "04/09/26", "Delicia", "INR",
                List.of(item("16/20 prawns", "2", "KG", "560", "1120",
                                new InvoiceReading.SkuMatch(9465L, "Prawns 16/20", "KG", new BigDecimal("360"), "Seafood")),
                        item("21/25 prawns", "2", "KG", "450", "900",
                                new InvoiceReading.SkuMatch(152L, "PRAWNS 21/25", "KG", new BigDecimal("300"), "Seafood")),
                        item("30/50 prawns", "2", "KG", "400", "800",
                                new InvoiceReading.SkuMatch(9001L, "Prawns 30/40", "KG", new BigDecimal("270"), "Seafood"))),
                null, null, null, new BigDecimal("2820"),
                new InvoiceReading.SupplierMatch(SUPPLIER_ID, SUPPLIER_NAME)));
    }

    private static InvoiceReading.Item item(String name, String qty, String unit, String price, String total,
                                            InvoiceReading.SkuMatch sku) {
        return new InvoiceReading.Item(name, new BigDecimal(qty), unit, new BigDecimal(price), new BigDecimal(total),
                new BigDecimal(total), BigDecimal.ZERO, sku);
    }
}
