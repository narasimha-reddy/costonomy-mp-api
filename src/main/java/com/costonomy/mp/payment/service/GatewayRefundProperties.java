package com.costonomy.mp.payment.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Timings and limits of gateway refunds (D-204). */
@Configuration
@ConfigurationProperties(prefix = "costonomy.mp.payments.gateway-refund")
@Getter
@Setter
public class GatewayRefundProperties {

    private Duration lease = Duration.ofMinutes(3);
    private int maxAttempts = 6;
    private Duration retryBackoff = Duration.ofMinutes(2);
    private Duration maxBackoff = Duration.ofHours(2);
    private Duration recheckAfter = Duration.ofMinutes(5);
    /** Questions to the gateway about one unknown refund before it is handed to a person. */
    private int maxLookups = 12;
    /** A recorded refund is sent by the sweep once it is this old, so the caller's own immediate send wins when it can. */
    private Duration sweepGrace = Duration.ofSeconds(20);
    /** Not yet sent after this long, a refund is overdue and appears in the finance queue. */
    private Duration overdueAfter = Duration.ofMinutes(30);
    private int batch = 25;
    private boolean jobsEnabled = true;
}
