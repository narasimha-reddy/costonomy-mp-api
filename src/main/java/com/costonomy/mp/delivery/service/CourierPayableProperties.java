package com.costonomy.mp.delivery.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Timings and limits of paying couriers (D-204). */
@Configuration
@ConfigurationProperties(prefix = "costonomy.mp.delivery.courier-payable")
@Getter
@Setter
public class CourierPayableProperties {

    /** How long one payment attempt may hold a payable before it is taken to have been lost (and asked about). */
    private Duration lease = Duration.ofMinutes(5);

    /** Payment attempts before a payable stops retrying by itself and waits for a person. */
    private int maxAttempts = 5;

    /** First retry delay after a definite failure; doubles each time up to {@link #maxBackoff}. */
    private Duration retryBackoff = Duration.ofMinutes(5);

    private Duration maxBackoff = Duration.ofHours(6);

    /** How soon an ambiguous payment is asked about again. */
    private Duration recheckAfter = Duration.ofMinutes(10);

    /** Questions to the rail about one ambiguous payment before it is handed to a person. */
    private int maxLookups = 12;

    /** After this long due and unpaid, a payable is overdue and appears in the finance queue. */
    private Duration overdueAfter = Duration.ofHours(72);

    /** Payables handled per job pass. */
    private int batch = 25;

    /** Deliveries the payable sync handles per batch. */
    private int syncBatch = 200;

    /** Batches one job pass may run per source, so a long backlog drains in a few passes and one pass stays bounded. */
    private int syncMaxBatchesPerPass = 5;

    /**
     * The cursor only reads deliveries older than this, so a transaction that commits a little late is still read
     * (after-commit syncs cover the freshest changes in the meantime).
     */
    private Duration syncSettleLag = Duration.ofSeconds(30);

    /** An eligible delivery with no payable for this long is an alert, not a delay. */
    private Duration missingAlertAfter = Duration.ofMinutes(10);

    private boolean jobsEnabled = true;
}
