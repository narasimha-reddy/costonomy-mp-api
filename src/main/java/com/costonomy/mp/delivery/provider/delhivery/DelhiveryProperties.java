package com.costonomy.mp.delivery.provider.delhivery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the Delhivery delivery provider.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.delhivery")
@Data
public class DelhiveryProperties {

    private boolean enabled = false;
    private String baseUrl = "https://track.delhivery.com";
    private String apiToken;
    private String clientId;
    private Duration timeout = Duration.ofSeconds(5);
    private int rateLimitRps = 20;
}
