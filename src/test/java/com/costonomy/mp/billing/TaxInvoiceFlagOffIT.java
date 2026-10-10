package com.costonomy.mp.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The default configuration: the feature is off, so the routes do not exist and nothing is ever written (D-133). */
class TaxInvoiceFlagOffIT extends TaxInvoiceTestBase {

    @Test
    @DisplayName("with the feature off all five routes are 404 and no invoice, credit note or number is created")
    void everythingIsNotFound() throws Exception {
        var seller = newSeller(freshGstin(), "0207");
        var p = placeNew(seller);
        readyAt(p, "9.6");
        String base = "/api/v1/supplier-orders/" + p.orderId();

        assertThat(post(seller.token(), generatePath(p), java.util.Map.of()).status()).isEqualTo(404);
        for (String path : new String[] {"/tax-invoice", "/credit-notes", "/tax-invoice/tally-xml",
                "/tax-invoice/gstr1-csv"}) {
            assertThat(get(seller.token(), base + path).status()).describedAs(path).isEqualTo(404);
            assertThat(get(p.buyer().token(), base + path).status()).describedAs(path).isEqualTo(404);
        }

        assertThat(invoiceRows(p.orderId())).isZero();
        assertThat(sequenceRows(seller.gstin())).isZero();

        // A rejection at the door issues no credit note either: nothing listens while the feature is off.
        assertThat(receive(p, "9.5", "0", "0.1").status()).isEqualTo(200);
        deliverReceivingCompleted(p.orderId());
        assertThat(creditNoteRows(p.orderId())).isZero();
    }
}
