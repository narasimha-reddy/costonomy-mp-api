package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.ReliabilitySettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link ReliabilitySettings} from {@code costonomy.mp.delivery.reliability.*} (D-197 addendum). */
@Configuration
@ConfigurationProperties(prefix = "costonomy.mp.delivery.reliability")
public class ReliabilityProperties extends ReliabilitySettings {
}
