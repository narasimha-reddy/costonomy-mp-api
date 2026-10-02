package com.costonomy.mp.delivery.provider.shadowfax;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the Shadowfax delivery provider.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.shadowfax")
@Data
public class ShadowfaxProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api-star.shadowfax.in";
    private String authToken;
    private String orderType = "marketplace";
    private Duration timeout = Duration.ofSeconds(5);
    private int rateLimitRps = 20;
}
