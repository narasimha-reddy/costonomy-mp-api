package com.costonomy.mp.delivery.provider.shiprocket;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the Shiprocket delivery provider.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.shiprocket")
@Data
public class ShiprocketProperties {

    private boolean enabled = false;
    private String baseUrl = "https://apiv2.shiprocket.in";
    private String apiToken;
    private String email;
    private String password;
    private Duration timeout = Duration.ofSeconds(5);
    private int rateLimitRps = 20;
}
