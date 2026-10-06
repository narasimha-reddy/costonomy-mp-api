package com.costonomy.mp.billing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What an invoice needs that only a person can supply, collected in one pass so the 422 names everything that is
 * missing rather than the first thing (D-133). Nothing is defaulted, guessed or substituted: a statutory document
 * with a made-up GSTIN, state or HSN code is worse than no document.
 *
 * <p>Checks presence and the shape the document depends on (a GSTIN that opens with a known state code), not
 * registration status or the GSTIN checksum; those are on the adviser's list.
 */
public final class InvoiceRequirements {

    /** The two places of supply a document needs, resolved once. */
    public record Parties(GstState supplierState, GstState placeOfSupply) {

        public boolean interState() {
            return supplierState != placeOfSupply;
        }
    }

    private InvoiceRequirements() {
    }

    /**
     * The names of everything missing, empty when the invoice can be issued. Names are stable field paths a client
     * can show next to the right input, such as {@code supplier.gstin} or {@code line[41 Fresh Milk].hsnCode}.
     */
    public static List<String> missing(BillingDirectory.SupplierParty supplier, BillingDirectory.BuyerParty buyer,
                                       List<BillingDirectory.Line> lines) {
        var missing = new ArrayList<String>();

        if (supplier == null) {
            missing.add("supplier");
        } else {
            if (blank(supplier.legalName())) {
                missing.add("supplier.legalName");
            }
            if (blank(supplier.gstin())) {
                missing.add("supplier.gstin");
            } else if (GstState.ofGstin(supplier.gstin()).isEmpty() || supplier.gstin().trim().length() != 15) {
                missing.add("supplier.gstin (not a valid GSTIN)");
            }
            if (blank(supplier.address())) {
                missing.add("supplier.address");
            }
            if (blank(supplier.state())) {
                missing.add("supplier.state");
            }
        }

        if (buyer == null) {
            missing.add("buyer");
        } else {
            if (blank(buyer.name())) {
                missing.add("buyer.name");
            }
            if (blank(buyer.address())) {
                missing.add("buyer.address");
            }
            if (placeOfSupply(buyer).isEmpty()) {
                missing.add("buyer.placeOfSupply (the outlet's state is missing or not a recognised state)");
            }
        }

        for (var line : lines) {
            if (line.suppliedQuantity() == null || line.suppliedQuantity().signum() <= 0) {
                continue;   // nothing was supplied on this line, so it is not invoiced and needs nothing
            }
            String label = "line[" + line.id() + (blank(line.productName()) ? "" : " " + line.productName()) + "]";
            if (blank(line.productName())) {
                missing.add(label + ".productName");
            }
            if (blank(line.hsnCode())) {
                missing.add(label + ".hsnCode");
            }
            if (blank(line.unit())) {
                missing.add(label + ".unit");
            }
        }
        return missing;
    }

    /** Resolved states, only valid when {@link #missing} found nothing. */
    public static Parties parties(BillingDirectory.SupplierParty supplier, BillingDirectory.BuyerParty buyer) {
        return new Parties(GstState.ofGstin(supplier.gstin()).orElseThrow(),
                placeOfSupply(buyer).orElseThrow());
    }

    /** Where the supply is made: the buyer's registration state when it has a GSTIN, else the outlet's state. */
    private static Optional<GstState> placeOfSupply(BillingDirectory.BuyerParty buyer) {
        var fromGstin = GstState.ofGstin(buyer.gstin());
        return fromGstin.isPresent() ? fromGstin : GstState.ofName(buyer.state());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
