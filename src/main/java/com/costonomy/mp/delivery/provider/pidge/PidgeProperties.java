package com.costonomy.mp.delivery.provider.pidge;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration properties for the Pidge Smart Dispatch integration.
 *
 * <p>Values are populated from {@code costonomy.mp.pidge.*} in environment
 * properties or application configuration.
 */
@Configuration
@ConfigurationProperties(prefix = "costonomy.mp.pidge")
@Getter
@Setter
public class PidgeProperties {

    /** Pidge API base URL. */
    private String baseUrl = "https://api.pidge.in";

    /** API Token generated in the Pidge dashboard under Channel Integration. */
    private String apiToken;

    /** API Secret for signing requests / authentication. */
    private String apiSecret;

    /** Webhook verification secret for HMAC-SHA256 callback validation. */
    private String webhookSecret;

    /** Integration channel name registered in Pidge (e.g. MandiMarketplace). */
    private String channelName = "MandiMarketplace";

    /** SMART or MANUAL allocation mode. */
    private String allocationMode = "SMART";

    /** Connect and read timeout for Pidge HTTP calls. */
    private Duration timeout = Duration.ofSeconds(5);

    /** Max requests per second allowed to Pidge API (token bucket rate limit). */
    private int rateLimitRps = 20;

    /** Default unassigned waterfall timeout before cascading. */
    private Duration unassignedTimeout = Duration.ofMinutes(3);
}
