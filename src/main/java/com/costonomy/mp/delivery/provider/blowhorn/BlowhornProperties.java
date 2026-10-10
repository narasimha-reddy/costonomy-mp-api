package com.costonomy.mp.delivery.provider.blowhorn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the Blowhorn delivery provider.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.blowhorn")
@Data
public class BlowhornProperties {

    private boolean enabled = false;
    private String baseUrl = "https://blowhorn.com/api";
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(5);
    private int rateLimitRps = 20;
}
