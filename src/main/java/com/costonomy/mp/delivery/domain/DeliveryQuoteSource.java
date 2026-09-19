package com.costonomy.mp.delivery.domain;

/** Where a delivery fee came from. D-091. */
public enum DeliveryQuoteSource {
    /** A provider priced this run. */
    QUOTED,
    /**
     * The rate card priced it, because no provider could be reached.
     *
     * <p>An outage must not block every order, but a figure nobody quoted should
     * not be indistinguishable from one somebody did.
     */
    ESTIMATED
}
