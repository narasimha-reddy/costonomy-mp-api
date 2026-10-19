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

    private static final int BATCH_SIZE = 50;
    private static final java.time.Duration STALE_CLAIM_TIMEOUT = java.time.Duration.ofMinutes(2);

    private final DeliveryRepository deliveries;
    private final DeliveryProviderRegistry registry;
    private final DeliveryEventService eventService;

    @Scheduled(fixedDelayString = "${costonomy.mp.delivery.poll-interval:PT30S}",
            initialDelayString = "${costonomy.mp.delivery.poll-initial-delay:PT0S}")
    @SchedulerLock(name = "delivery-poll", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void pollActiveDeliveries() {
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant staleThreshold = now.minus(STALE_CLAIM_TIMEOUT);

        List<Long> dueIds = deliveries.findDueDeliveryIdsForPolling(staleThreshold, BATCH_SIZE);
        for (Long id : dueIds) {
            String claimToken = java.util.UUID.randomUUID().toString();
            int claimed = deliveries.claimDeliveryForPoll(id, now, staleThreshold, claimToken);
            if (claimed == 0) {
                continue;
            }
            try {
                pollOne(id);
            } catch (Exception ex) {
                log.error("Unexpected error during polling delivery {}", id, ex);
            } finally {
                deliveries.releaseDeliveryAfterPoll(id, claimToken, java.time.Instant.now());
            }
        }
    }

    public void pollOne(Long deliveryId) {
        Delivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null
                || delivery.getMode() != DeliveryMode.COSTONOMY
                || delivery.getProviderDeliveryId() == null) {
            return;
        }

        var adapter = registry.adapter(delivery.getProviderCode());
        if (adapter == null) {
            return;
        }

        try {
            var state = adapter.status(delivery.getProviderDeliveryId());
            if (state == null) {
                return;
            }

            boolean trackable = eventService.applyProviderSnapshot(delivery.getId(), state);
            if (adapter.supportsTracking() && trackable) {
                try {
                    var location = adapter.location(delivery.getProviderDeliveryId());
                    if (location != null) {
                        eventService.recordLocation(delivery.getId(), location.latitude(),
                                location.longitude(), location.bearing(),
                                location.speedKmph(), location.recordedAt());
                    }
                } catch (Exception locEx) {
                    log.debug("Could not fetch location for delivery {}: {}", delivery.getId(), locEx.getMessage());
                }
            }
        } catch (DeliveryProviderException ex) {
            log.debug("Could not poll delivery {}: {}", delivery.getId(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Could not poll delivery {}", delivery.getId(), ex);
        }
    }
}
