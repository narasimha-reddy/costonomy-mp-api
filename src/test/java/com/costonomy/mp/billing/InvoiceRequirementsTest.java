package com.costonomy.mp.billing;

import com.costonomy.mp.billing.service.BillingDirectory.BuyerParty;
import com.costonomy.mp.billing.service.BillingDirectory.Line;
import com.costonomy.mp.billing.service.BillingDirectory.SupplierParty;
import com.costonomy.mp.billing.service.GstState;
import com.costonomy.mp.billing.service.InvoiceRequirements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceRequirementsTest {

    private static final SupplierParty SUPPLIER =
            new SupplierParty(1L, "Fresh Dairy LLP", "36AABCU9603R1ZX", "Kondapur, Hyderabad, 500084", "Telangana");
    private static final BuyerParty BUYER =
            new BuyerParty(2L, "Curry Leaf", null, "Hitech City, Hyderabad, 500081", "Telangana");

    private static Line line(long id, String name, String hsn, String qty) {
        return new Line(id, name, hsn, new BigDecimal(qty), null, "KG", new BigDecimal("100"), new BigDecimal("5"),
                new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("1050"), null, null, null);
    }

    @Test
    @DisplayName("complete details need nothing")
    void complete() {
        assertThat(InvoiceRequirements.missing(SUPPLIER, BUYER, List.of(line(41, "Fresh Milk", "0401", "10"))))
                .isEmpty();
    }

    @Test
    @DisplayName("a missing supplier GSTIN is named, and only that")
    void missingGstin() {
        var supplier = new SupplierParty(1L, "Fresh Dairy LLP", null, "Kondapur", "Telangana");

        assertThat(InvoiceRequirements.missing(supplier, BUYER, List.of(line(41, "Fresh Milk", "0401", "10"))))
                .containsExactly("supplier.gstin");
    }

    @Test
    @DisplayName("every gap is listed together, not just the first")
    void everyGapIsListed() {
        var supplier = new SupplierParty(1L, " ", "", null, null);
        var buyer = new BuyerParty(2L, "", null, "", "Atlantis");

        var missing = InvoiceRequirements.missing(supplier, buyer, List.of(
                line(41, "Fresh Milk", null, "10"), line(42, "Paneer", "  ", "5")));

        assertThat(missing).containsExactly("supplier.legalName", "supplier.gstin", "supplier.address",
                "supplier.state", "buyer.name", "buyer.address",
                "buyer.placeOfSupply (the outlet's state is missing or not a recognised state)",
                "line[41 Fresh Milk].hsnCode", "line[42 Paneer].hsnCode");
    }

    @Test
    @DisplayName("a GSTIN that is not 15 characters or does not open with a known state code is refused")
    void invalidGstin() {
        for (String gstin : new String[] {"36AABCU9603R1Z", "99AABCU9603R1ZX", "ABAABCU9603R1ZX"}) {
            var supplier = new SupplierParty(1L, "Fresh Dairy LLP", gstin, "Kondapur", "Telangana");
            assertThat(InvoiceRequirements.missing(supplier, BUYER, List.of()))
                    .describedAs(gstin).containsExactly("supplier.gstin (not a valid GSTIN)");
        }
    }

    @Test
    @DisplayName("a line nothing was supplied on needs no HSN code")
    void unsuppliedLineNeedsNothing() {
        assertThat(InvoiceRequirements.missing(SUPPLIER, BUYER, List.of(line(41, "Fresh Milk", null, "0"))))
                .isEmpty();
    }

    @Test
    @DisplayName("the place of supply is the buyer's GSTIN state when it has one, else the outlet's state")
    void placeOfSupply() {
        var registered = new BuyerParty(2L, "Curry Leaf", "29AABCU9603R1ZX", "Bengaluru", "Telangana");

        assertThat(InvoiceRequirements.parties(SUPPLIER, registered).placeOfSupply()).isEqualTo(GstState.KARNATAKA);
        assertThat(InvoiceRequirements.parties(SUPPLIER, registered).interState()).isTrue();
        assertThat(InvoiceRequirements.parties(SUPPLIER, BUYER).placeOfSupply()).isEqualTo(GstState.TELANGANA);
        assertThat(InvoiceRequirements.parties(SUPPLIER, BUYER).interState()).isFalse();
    }
}
