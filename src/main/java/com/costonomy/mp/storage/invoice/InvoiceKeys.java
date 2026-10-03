package com.costonomy.mp.storage.invoice;

import java.time.YearMonth;
import java.util.UUID;

/**
 * {@code invoices/<outletId>/<yyyy-MM>/<uuid>-p<page>.<ext>}. The extension comes from the sniffed
 * type, and nothing else in the key comes from the client: not the file name, not the content type.
 */
public final class InvoiceKeys {

    private InvoiceKeys() {
    }

    public static String forPage(long outletId, YearMonth month, UUID id, int page, String extension) {
        if (page < 1 || !extension.matches("[a-z0-9]{2,5}")) {
            throw new IllegalArgumentException("bad invoice key part");
        }
        return "invoices/%d/%s/%s-p%d.%s".formatted(outletId, month, id, page, extension);
    }
}
