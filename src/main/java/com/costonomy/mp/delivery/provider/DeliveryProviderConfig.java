package com.costonomy.mp.delivery.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Registers the delivery adapters. Doc 06 §11, doc 10 §6.
 *
 * <p><b>Two mocks, not one.</b> Doc 06 §4's rule is "lowest cost meeting the
 * required ETA", and with a single candidate every selection strategy produces the
 * same answer — a selection bug would be invisible until a second real provider
 * was added in production. The two differ the way couriers do: one faster and
 * dearer, one cheaper and slower with a smaller service area.
 *
 * <p>Declared as {@code @Bean} methods rather than annotated classes because the
 * same class is registered twice with different configuration. It is also why
 * {@code MockDeliveryProvider} has no {@code @Service} on it — a stereotype
 * annotation there would quietly add a third, unconfigured instance.
 */
@Configuration
@ConditionalOnProperty(name = "costonomy.mp.providers.delivery", havingValue = "MOCK",
        matchIfMissing = true)
public class DeliveryProviderConfig {

    /** Faster, dearer, wider radius. */
    @Bean
    public MockDeliveryProvider mockExpressDeliveryProvider() {
        return new MockDeliveryProvider("MOCK_EXPRESS",
                new BigDecimal("40.00"), new BigDecimal("12.00"), 4, 25.0);
    }

    /** Cheaper, slower, and refuses long routes — so serviceability is a real answer. */
    @Bean
    public MockDeliveryProvider mockSaverDeliveryProvider() {
        return new MockDeliveryProvider("MOCK_SAVER",
                new BigDecimal("25.00"), new BigDecimal("8.00"), 7, 12.0);
    }
}
