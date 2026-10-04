package com.costonomy.mp.billing.service;

import com.costonomy.mp.common.error.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The switch for statutory tax invoices (D-133). Off by default: who issues the invoice, and whether the figures
 * pass a tax adviser's review, are not settled, and a document labelled statutory must not exist before they are.
 * When off, every billing route answers 404 and nothing listens to the events that would issue a credit note.
 */
@Component
public class BillingFeature {

    private final boolean enabled;

    public BillingFeature(@Value("${costonomy.mp.billing.tax-invoices.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** First call in every route: when the feature is off the route does not exist. */
    public void requireEnabled() {
        if (!enabled) {
            throw new NotFoundException("TaxInvoice", null);
        }
    }
}
