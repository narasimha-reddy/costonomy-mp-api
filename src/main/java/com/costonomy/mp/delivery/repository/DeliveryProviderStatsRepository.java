package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryProviderStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DeliveryProviderStatsRepository
        extends JpaRepository<DeliveryProviderStats, Long> {

    Optional<DeliveryProviderStats> findByProviderCodeAndWindowDate(
            String providerCode, LocalDate windowDate);

    /**
     * The most recent {@code days} daily rows for each provider, youngest first.
     * Used by {@code DeliverySelection} to build a rolling reliability window.
     */
    @Query("""
            SELECT s FROM DeliveryProviderStats s
            WHERE s.windowDate >= :since
            ORDER BY s.providerCode ASC, s.windowDate DESC
            """)
    List<DeliveryProviderStats> findSince(@Param("since") LocalDate since);

    /** All rows for one provider, for admin/ops inspection. */
    List<DeliveryProviderStats> findByProviderCodeOrderByWindowDateDesc(String providerCode);
}
