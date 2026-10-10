package com.costonomy.mp.delivery.provider.borzo;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration properties for the Borzo Business API integration.
 *
 * <p>Values are populated from {@code costonomy.mp.borzo.*}. {@code enabled} is
 * this adapter's own activation flag — deliberately independent of
 * {@code costonomy.mp.providers.delivery}, which Pidge's
 * {@code @ConditionalOnProperty} is pinned to a single value of. Borzo needs to
 * run alongside Pidge, not in place of it, so it cannot share that switch.
 */
@Configuration
@ConfigurationProperties(prefix = "costonomy.mp.borzo")
@Getter
@Setter
public class BorzoProperties {

    /** Whether the Borzo adapter bean is registered at all. */
    private boolean enabled = false;

    /** Borzo Business API base URL — sandbox or production, same paths on both. */
    private String baseUrl = "https://robotapitest-in.borzodelivery.com/api/business/1.8";

    /** {@code X-DV-Auth-Token}, issued in the Borzo Personal Cabinet. */
    private String authToken;

    /**
     * Borzo {@code vehicle_type_id}. Only {@code 8} (motorbike) has been verified
     * against the sandbox — see {@link BorzoApiClient}. There is no mapping from
     * our {@code VehicleType} enum here on purpose: guessing an unverified id for
     * a three-wheeler or truck would silently book the wrong vehicle.
     */
    private int vehicleTypeId = 8;

    /** Connect and read timeout for Borzo HTTP calls. */
    private Duration timeout = Duration.ofSeconds(5);

    /** Max requests per second allowed to Borzo's API (local token-bucket guardrail). */
    private int rateLimitRps = 20;
}
