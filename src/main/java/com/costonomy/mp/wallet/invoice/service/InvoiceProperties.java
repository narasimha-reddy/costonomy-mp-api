package com.costonomy.mp.wallet.invoice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** The bill feature's settings (D-113), read once. */
@Component
public class InvoiceProperties {

    final int dailyCap;
    final Duration linkTtl;
    final int maxAttempts;
    final Duration retryMinAge;
    final String linkBaseUrl;
    /** D-115: the cost app unavailable; the next try waits base, 2×base, 4×base... up to max. */
    final Duration unavailableBackoffBase;
    final Duration unavailableBackoffMax;
    /** D-115: after this many tries that found the cost app unavailable the bill is UNREADABLE. */
    final int maxUnavailable;

    public InvoiceProperties(
            @Value("${costonomy.mp.invoices.daily-cap:20}") int dailyCap,
            @Value("${costonomy.mp.invoices.link-ttl:PT5M}") Duration linkTtl,
            @Value("${costonomy.mp.invoices.max-attempts:5}") int maxAttempts,
            @Value("${costonomy.mp.invoices.retry-min-age:PT1M}") Duration retryMinAge,
            @Value("${costonomy.mp.invoices.link-base-url:http://localhost:7070/costonomy-mp-api}") String linkBaseUrl,
            @Value("${costonomy.mp.invoices.unavailable-backoff-base:PT1M}") Duration unavailableBackoffBase,
            @Value("${costonomy.mp.invoices.unavailable-backoff-max:PT30M}") Duration unavailableBackoffMax,
            @Value("${costonomy.mp.invoices.max-unavailable:48}") int maxUnavailable) {
        this.dailyCap = dailyCap;
        this.linkTtl = linkTtl;
        this.maxAttempts = maxAttempts;
        this.retryMinAge = retryMinAge;
        this.linkBaseUrl = linkBaseUrl.replaceAll("/+$", "");
        this.unavailableBackoffBase = unavailableBackoffBase;
        this.unavailableBackoffMax = unavailableBackoffMax;
        this.maxUnavailable = maxUnavailable;
    }

    /** The wait before the next try after the {@code n}-th time (1, 2, ...) the cost app was unavailable. */
    Duration backoff(int n) {
        Duration d = unavailableBackoffBase;
        for (int i = 1; i < n && d.compareTo(unavailableBackoffMax) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(unavailableBackoffMax) > 0 ? unavailableBackoffMax : d;
    }
}
