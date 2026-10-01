package com.costonomy.mp.wallet.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * How much money a wallet may hold and take in (D-107).
 *
 * <p><b>These stand in for KYC tiers.</b> A wallet that anyone can fill without
 * limit is a place to park money we know nothing about the owner of, which is
 * what identity checks exist to prevent. KYC is not being built, so the limits
 * are set low enough that the exposure is small without it; a future KYC tier
 * would raise them per outlet, and this is the one place they are read from so
 * that is a change here and not a hunt through the code.
 *
 * <p>They are compared against ledger amounts with {@code compareTo}, never
 * {@code equals}, so a property written as {@code 100000} and a balance stored as
 * {@code 100000.0000} are the same figure.
 */
@Component
public class WalletLimits {

    /** A month is a calendar month where the restaurants are, not in UTC. */
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final BigDecimal maxBalance;
    private final BigDecimal monthlyTopUpLimit;
    private final BigDecimal minTopUp;
    private final BigDecimal maxTopUp;

    public WalletLimits(
            @Value("${costonomy.mp.wallet.max-balance:100000}") BigDecimal maxBalance,
            @Value("${costonomy.mp.wallet.monthly-top-up-limit:1000000}") BigDecimal monthlyTopUpLimit,
            @Value("${costonomy.mp.wallet.min-top-up:10}") BigDecimal minTopUp,
            @Value("${costonomy.mp.wallet.max-top-up:100000}") BigDecimal maxTopUp) {
        this.maxBalance = maxBalance;
        this.monthlyTopUpLimit = monthlyTopUpLimit;
        this.minTopUp = minTopUp;
        this.maxTopUp = maxTopUp;
    }

    /** The most a wallet may hold. */
    public BigDecimal maxBalance() {
        return maxBalance;
    }

    /** The most that may be added through top-ups in one calendar month. */
    public BigDecimal monthlyTopUpLimit() {
        return monthlyTopUpLimit;
    }

    public BigDecimal minTopUp() {
        return minTopUp;
    }

    public BigDecimal maxTopUp() {
        return maxTopUp;
    }

    /** The start of the calendar month (Asia/Kolkata) that {@code now} falls in. */
    public Instant monthStart(Instant now) {
        return LocalDate.ofInstant(now, ZONE).withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
    }

    /** The start of the month after that one; the month is {@code [start, end)}. */
    public Instant monthEnd(Instant now) {
        return LocalDate.ofInstant(now, ZONE).withDayOfMonth(1).plusMonths(1).atStartOfDay(ZONE).toInstant();
    }
}
