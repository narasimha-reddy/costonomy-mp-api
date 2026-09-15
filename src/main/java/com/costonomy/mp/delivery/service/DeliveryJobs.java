package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Asks providers what has happened, for the ones that will not tell us.
 *
 * <p>Doc 06 §9 prefers webhooks, and {@code DeliveryEventService} handles them
 * whenever a provider sends one. This is the fallback for providers without
 * callbacks, and the safety net for callbacks that never arrive — the delivery
 * equivalent of {@code PaymentJobs.reconcileStale}, and needed for the same
 * reason: a restaurant whose driver arrived twenty minutes ago should not still be
 * watching "finding a driver" because one HTTP call was lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryJobs {

    /** Deliveries still expecting something to happen. */
    private static final List<DeliveryStatus> ACTIVE = List.of(
            DeliveryStatus.PROVIDER_SELECTED, DeliveryStatus.DRIVER_ASSIGNED,
            DeliveryStatus.DRIVER_AT_PICKUP, DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT, DeliveryStatus.ARRIVED_AT_DESTINATION);

    private final DeliveryRepository deliveries;
    private final DeliveryProviderRegistry registry;
    private final DeliveryEventService eventService;

    @Scheduled(fixedDelayString = "${costonomy.mp.delivery.poll-interval:PT30S}")
    @SchedulerLock(name = "delivery-poll", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    @Transactional
    public void pollActiveDeliveries() {
        for (Delivery delivery : deliveries.findByStatusIn(ACTIVE)) {
            // Supplier own delivery has no provider to ask. Doc 06 §2.
            if (delivery.getMode() != DeliveryMode.COSTONOMY
                    || delivery.getProviderDeliveryId() == null) {
                continue;
            }

            var adapter = registry.adapter(delivery.getProviderCode());
            if (adapter == null) {
                continue;
            }

            try {
                var state = adapter.status(delivery.getProviderDeliveryId());

                if (state.driverName() != null) {
                    eventService.recordDriver(delivery, state.driverName(),
                            state.driverPhone(), state.driverVehicle());
                }
                eventService.recordEta(delivery, state.etaMinutes());

                // Oldest first, so a burst of events applies in the order they
                // happened rather than the order they were listed.
                var events = new java.util.ArrayList<>(state.events());
                java.util.Collections.reverse(events);
                events.forEach(event -> eventService.apply(delivery, event));

                if (adapter.supportsTracking() && delivery.getStatus().isTrackable()) {
                    var location = adapter.location(delivery.getProviderDeliveryId());
                    if (location != null) {
                        eventService.recordLocation(delivery, location.latitude(),
                                location.longitude(), location.bearing(),
                                location.speedKmph(), location.recordedAt());
                    }
                }

            } catch (DeliveryProviderException ex) {
                // Unreachable providers are normal. The next sweep asks again.
                log.debug("Could not poll delivery {}: {}", delivery.getId(), ex.getMessage());
            } catch (RuntimeException ex) {
                // One stuck delivery must not stop the rest — the next one may be
                // the one whose driver has been waiting at a locked door.
                log.error("Could not poll delivery {}", delivery.getId(), ex);
            }
        }
    }
}
