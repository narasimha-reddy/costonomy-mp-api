package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryProviderMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeliveryProviderMetricsRepository
        extends JpaRepository<DeliveryProviderMetrics, Long> {

    Optional<DeliveryProviderMetrics> findByProviderCodeAndWindowStart(
            String providerCode, Instant windowStart);

    @Query("""
            SELECT m FROM DeliveryProviderMetrics m
            WHERE m.windowStart >= :since
            ORDER BY m.providerCode ASC, m.windowStart DESC
            """)
    List<DeliveryProviderMetrics> findSince(@Param("since") Instant since);

    List<DeliveryProviderMetrics> findByProviderCodeOrderByWindowStartDesc(String providerCode);
}
