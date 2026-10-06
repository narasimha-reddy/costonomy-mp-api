package com.costonomy.mp.delivery.provider.porter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the Porter delivery provider.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.porter")
@Data
public class PorterProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api.porter.in";
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(5);
    private int rateLimitRps = 20;
}
