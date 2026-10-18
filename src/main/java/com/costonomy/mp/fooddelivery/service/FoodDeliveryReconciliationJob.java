package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Polls provider status and location for active in-flight food deliveries
 * when webhook updates have not been received recently (D-197).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodDeliveryReconciliationJob {

    private static final List<FoodDeliveryStatus> IN_FLIGHT = List.of(
            FoodDeliveryStatus.PROVIDER_SELECTED,
            FoodDeliveryStatus.DRIVER_ASSIGNED,
            FoodDeliveryStatus.AT_PICKUP,
            FoodDeliveryStatus.PICKED_UP,
            FoodDeliveryStatus.OUT_FOR_DELIVERY,
            FoodDeliveryStatus.ARRIVED
    );

    private final FoodDeliveryRepository deliveries;
    private final DeliveryProviderRegistry registry;
    private final FoodDeliveryEventService eventService;

    private static final int BATCH_SIZE = 50;
    private static final Duration STALE_CLAIM_TIMEOUT = Duration.ofMinutes(2);

    @Value("${costonomy.mp.food-delivery.reconciliation.enabled:true}")
    private boolean enabled = true;

    @Value("${costonomy.mp.food-delivery.reconciliation.stale-threshold:PT2M}")
    private Duration staleThreshold = Duration.ofMinutes(2);

    @Scheduled(fixedDelayString = "${costonomy.mp.food-delivery.reconciliation.poll:PT1M}")
    @SchedulerLock(name = "food-delivery-reconciliation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void reconcileActiveDeliveries() {
        runReconciliation(Instant.now());
    }

    public int runReconciliation(Instant now) {
        if (!enabled) {
            return 0;
        }

        Instant updateCutoff = now.minus(staleThreshold);
        Instant claimCutoff = now.minus(STALE_CLAIM_TIMEOUT);
        List<String> statusNames = IN_FLIGHT.stream().map(Enum::name).toList();

        List<Long> dueIds = deliveries.findDueDeliveryIdsForPolling(statusNames, updateCutoff, claimCutoff, BATCH_SIZE);
        int reconciled = 0;

        for (Long id : dueIds) {
            String claimToken = java.util.UUID.randomUUID().toString();
            int claimed = deliveries.claimDeliveryForPoll(id, now, claimCutoff, claimToken);
            if (claimed == 0) {
                continue;
            }
            try {
                if (reconcileOne(id, now)) {
                    reconciled++;
                }
            } catch (Exception ex) {
                log.warn("Reconciliation poll failed for food delivery {}: {}", id, ex.getMessage());
            } finally {
                deliveries.releaseDeliveryAfterPoll(id, claimToken, Instant.now());
            }
        }

        if (!dueIds.isEmpty()) {
            log.info("Food delivery reconciliation: {} active deliveries checked, {} updated", dueIds.size(), reconciled);
        }
        return reconciled;
    }

    private boolean reconcileOne(Long deliveryId, Instant now) {
        var d = deliveries.findById(deliveryId).orElse(null);
        if (d == null || d.getProviderCode() == null || d.getProviderDeliveryId() == null) {
            return false;
        }

        var adapter = registry.adapter(d.getProviderCode());
        if (adapter == null) {
            return false;
        }

        boolean updated = false;
        var status = adapter.status(d.getProviderDeliveryId());
        if (status != null && status.status() != null) {
            var provEvent = new DeliveryProvider.ProviderEvent(
                    pollEventId(d.getProviderCode(), d.getProviderDeliveryId(), status.status()),
                    status.status(),
                    "Polled status from " + d.getProviderCode(),
                    now
            );
            eventService.apply(d, d.getProviderCode(), d.getProviderDeliveryId(), provEvent, null);

            if (status.driverName() != null || status.driverPhone() != null || status.driverVehicle() != null) {
                eventService.recordDriver(d, d.getProviderCode(), d.getProviderDeliveryId(),
                        status.driverName(), status.driverPhone(), status.driverVehicle());
            }
            updated = true;
        }

        if (adapter.supportsTracking()) {
            try {
                var loc = adapter.location(d.getProviderDeliveryId());
                if (loc != null && loc.latitude() != null && loc.longitude() != null) {
                    eventService.recordLocation(d, d.getProviderCode(), d.getProviderDeliveryId(),
                            loc.latitude(), loc.longitude(),
                            loc.bearing(), loc.speedKmph(), loc.recordedAt() != null ? loc.recordedAt() : now);
                }
            } catch (Exception locEx) {
                log.debug("Location poll failed for food delivery {}: {}", d.getId(), locEx.getMessage());
            }
        }
        return updated;
    }

    private static String pollEventId(String providerCode, String providerDeliveryId,
                                     DeliveryProvider.ProviderDeliveryStatus status) {
        String key = providerCode + "\u0000" + providerDeliveryId + "\u0000" + status.name();
        return "poll-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
