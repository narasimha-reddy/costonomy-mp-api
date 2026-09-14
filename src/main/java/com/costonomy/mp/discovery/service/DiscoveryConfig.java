package com.costonomy.mp.discovery.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Discovery beans that have a swappable default.
 *
 * <p>{@link SupplierPerformanceProvider} falls back to
 * {@link NoHistoryPerformanceProvider} until Phase 8 adds an order-derived
 * implementation. Declaring another bean of that type is then enough to take
 * over — nothing here changes.
 *
 * <p>The condition lives on a {@code @Bean} method rather than on the class,
 * which matters: {@code @ConditionalOnMissingBean} on a {@code @Service} is
 * evaluated during component scanning, where bean-definition order is undefined,
 * and it will happily exclude the only implementation there is. It is only
 * reliable on {@code @Bean} methods, which are processed after scanning.
 */
@Configuration
public class DiscoveryConfig {

    @Bean
    @ConditionalOnMissingBean(SupplierPerformanceProvider.class)
    public SupplierPerformanceProvider noHistoryPerformanceProvider() {
        return new NoHistoryPerformanceProvider();
    }
}
