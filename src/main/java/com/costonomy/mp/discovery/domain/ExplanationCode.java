package com.costonomy.mp.discovery.domain;

/**
 * Why an offer was recommended. Doc 07 §5.
 *
 * <p>The governing rule: <em>"Do not display an explanation that is not supported
 * by actual calculated data."</em> Every code below is emitted only when the
 * figure behind it exists and was actually measured — never as a default, and
 * never for a supplier whose history is too thin to mean anything.
 *
 * <p>The client renders these as the badge text §23A.13 requires ("Best value",
 * "Fastest", "Most reliable"). Copy lives in the client so it can be localised;
 * the code is the contract.
 */
public enum ExplanationCode {

    /** Lowest effective commercial total in the candidate set. */
    BEST_TOTAL_VALUE,

    /** Lowest estimated time to delivery. */
    FASTEST_AVAILABLE,

    /**
     * Fill rate at or above the configured threshold, over enough orders to mean
     * something. Unreachable until order history exists.
     */
    HIGH_FILL_RATE,

    /**
     * On-time delivery at or above the configured threshold, over enough orders.
     * Unreachable until order history exists.
     */
    RELIABLE_SUPPLIER,

    /**
     * Cheaper than this outlet's own past purchases of the product.
     *
     * <p>Requires purchase history, so it arrives with procurement (Phase 7+).
     * Listed here because doc 07 §5 names it, not because it can be emitted yet.
     */
    LOWER_HISTORICAL_COST,

    /**
     * Can cover the full requested quantity where others cannot.
     *
     * <p>Not in doc 07 §5's list, but it is a real, calculated reason and the one
     * that most often explains why the cheapest offer was not recommended.
     */
    FULL_QUANTITY_AVAILABLE,

    /**
     * New to the marketplace, ranked on price and speed alone.
     *
     * <p>Doc 07 §6's cold start, made visible. A restaurant deserves to know that
     * an offer carries no track record rather than being quietly shown one that
     * looks the same as an established supplier's.
     */
    NEW_SUPPLIER,
}
