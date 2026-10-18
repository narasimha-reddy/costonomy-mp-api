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
import java.util.List;

/**
 * Polls provider status and location for active in-flight food deliveries
 * when webhook updates have not been received recently (D-197).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodDeliveryReconciliationJob {

    private static final List<FoodDeliveryStatus> IN_FLIGHT = List.of(
            FoodDeliveryStatus.DRIVER_ASSIGNED,
            FoodDeliveryStatus.AT_PICKUP,
            FoodDeliveryStatus.PICKED_UP,
            FoodDeliveryStatus.OUT_FOR_DELIVERY,
            FoodDeliveryStatus.ARRIVED
    );

    private final FoodDeliveryRepository deliveries;
    private final DeliveryProviderRegistry registry;
    private final FoodDeliveryEventService eventService;

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

        Instant cutoff = now.minus(staleThreshold);
        var active = deliveries.findByStatusIn(IN_FLIGHT).stream()
                .filter(d -> d.getProviderCode() != null && d.getProviderDeliveryId() != null)
                .filter(d -> d.getLastProviderUpdateAt() == null || d.getLastProviderUpdateAt().isBefore(cutoff))
                .toList();

        int reconciled = 0;
        for (var d : active) {
            try {
                var adapter = registry.adapter(d.getProviderCode());
                if (adapter == null) {
                    continue;
                }

                var status = adapter.status(d.getProviderDeliveryId());
                if (status != null && status.status() != null) {
                    var provEvent = new DeliveryProvider.ProviderEvent(
                            "poll-" + d.getProviderDeliveryId() + "-" + now.toEpochMilli(),
                            status.status(),
                            "Polled status from " + d.getProviderCode(),
                            now
                    );
                    eventService.apply(d, provEvent, d.getProviderCode(), null);

                    if (status.driverName() != null || status.driverPhone() != null || status.driverVehicle() != null) {
                        eventService.recordDriver(d, status.driverName(), status.driverPhone(), status.driverVehicle());
                    }
                    reconciled++;
                }

                if (adapter.supportsTracking()) {
                    var loc = adapter.location(d.getProviderDeliveryId());
                    if (loc != null && loc.latitude() != null && loc.longitude() != null) {
                        eventService.recordLocation(d, loc.latitude(), loc.longitude(),
                                loc.bearing(), loc.speedKmph(), loc.recordedAt() != null ? loc.recordedAt() : now);
                    }
                }
            } catch (Exception ex) {
                log.warn("Reconciliation poll failed for food delivery {} with provider {}: {}",
                        d.getId(), d.getProviderCode(), ex.getMessage());
            }
        }

        if (!active.isEmpty()) {
            log.info("Food delivery reconciliation: {} active deliveries checked, {} updated", active.size(), reconciled);
        }
        return reconciled;
    }
}
