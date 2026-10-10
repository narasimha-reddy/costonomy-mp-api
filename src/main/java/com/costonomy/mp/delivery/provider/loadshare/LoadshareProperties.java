package com.costonomy.mp.delivery.provider.loadshare;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the LoadShare Networks delivery provider.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.loadshare")
@Data
public class LoadshareProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api.loadshare.net";
    private String customerCode;
    private String authToken;
    private Duration timeout = Duration.ofSeconds(5);
    private int rateLimitRps = 20;
}
