package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.List;

public interface DeliveryEventRepository extends JpaRepository<DeliveryEvent, Long> {

    List<DeliveryEvent> findByDeliveryIdOrderByOccurredAtAscIdAsc(Long deliveryId);

    /**
     * Store a provider event unless this provider already sent it. {@code INSERT IGNORE} turns the duplicate-key error
     * into "0 rows", which, unlike a caught exception, leaves the caller's transaction usable. Returns 1 if stored.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert ignore into delivery_event (delivery_id, event_type, status, provider_code, provider_event_id,
                                               provider_status, disposition, description, payload, occurred_at, created_at)
            values (:deliveryId, :eventType, :status, :providerCode, :providerEventId, :providerStatus, :disposition,
                    :description, :payload, :occurredAt, now(6))
            """, nativeQuery = true)
    int insertUnlessDuplicate(@Param("deliveryId") Long deliveryId, @Param("eventType") String eventType,
                              @Param("status") String status, @Param("providerCode") String providerCode,
                              @Param("providerEventId") String providerEventId,
                              @Param("providerStatus") String providerStatus,
                              @Param("disposition") String disposition, @Param("description") String description,
                              @Param("payload") String payload, @Param("occurredAt") Instant occurredAt);
}
