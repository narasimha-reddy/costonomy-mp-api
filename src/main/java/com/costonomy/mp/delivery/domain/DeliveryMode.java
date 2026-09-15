package com.costonomy.mp.delivery.domain;

/**
 * Who carries the goods. Doc 01 §20, doc 06 §2.
 *
 * <p>The distinction decides <b>who may move a delivery forward</b>, which is the
 * part that is easy to get wrong. On Costonomy delivery a supplier must not be
 * able to claim pickup or delivery (§23A.38) — only the provider's events say what
 * happened, because the supplier is not the one carrying it. On own delivery the
 * supplier <em>is</em> the courier, so they report their own progress, and there
 * is no tracking to show because there is no provider reporting positions.
 */
public enum DeliveryMode {

    /**
     * The supplier delivers. Restaurant pays the supplier's own fee, usually zero.
     * No live tracking, and Costonomy claims no operational responsibility.
     */
    SUPPLIER_OWN,

    /** Mandi dispatches a provider and the restaurant pays the platform fee. */
    COSTONOMY;

    /** Whether the supplier is the one reporting movement. */
    public boolean isSupplierReported() {
        return this == SUPPLIER_OWN;
    }

    /** Whether a driver position can ever exist for this mode. */
    public boolean isTracked() {
        return this == COSTONOMY;
    }
}
