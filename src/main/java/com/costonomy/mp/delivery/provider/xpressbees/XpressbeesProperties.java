package com.costonomy.mp.delivery.provider.xpressbees;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the Xpressbees delivery provider.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.xpressbees")
@Data
public class XpressbeesProperties {

    private boolean enabled = false;
    private String baseUrl = "https://shipment.xpressbees.com/api";
    private String token;
    private String email;
    private String password;
    private Duration timeout = Duration.ofSeconds(5);
    private int rateLimitRps = 20;
}
