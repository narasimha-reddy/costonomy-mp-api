package com.costonomy.mp.quickscan.domain;

/**
 * How a QuickScan payment is funded. D-106.
 *
 * <p>Only {@link #WALLET} works today. {@link #UPI} is reserved for a later
 * change — paying straight from a bank account via UPI rather than a prepaid
 * balance — so the column and the request shape do not need to change again.
 */
public enum QuickScanMethod {
    WALLET,
    UPI
}
