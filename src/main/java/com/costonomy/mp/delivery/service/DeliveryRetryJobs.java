package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Looks again for a delivery partner on deliveries nobody could take, and then offers the supplier delivering the
 * order themselves (D-151).
 *
 * <p>Not transactional, on purpose: a retry calls providers, so each one runs in its own transaction, after its claim
 * has committed (see {@link DeliveryRetryClaims}). One failing delivery never stops the others.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryRetryJobs {

    private final DeliveryRepository deliveries;
    private final DeliveryRetryClaims claims;
    private final DeliveryService deliveryService;

    @Value("${costonomy.mp.delivery.auto-retry.enabled:true}")
    private boolean enabled = true;

    @Value("${costonomy.mp.delivery.auto-retry.interval:PT2M}")
    private Duration interval = Duration.ofMinutes(2);

    @Value("${costonomy.mp.delivery.auto-retry.window:PT30M}")
    private Duration window = Duration.ofMinutes(30);

    @Value("${costonomy.mp.delivery.auto-retry.max:15}")
    private int maxRetries = 15;

    @Value("${costonomy.mp.delivery.auto-retry.batch:20}")
    private int batch = 20;

    @Value("${costonomy.mp.delivery.own-delivery-offer-after:PT45M}")
    private Duration offerAfter = Duration.ofMinutes(45);

    @Scheduled(fixedDelayString = "${costonomy.mp.delivery.auto-retry.poll:PT1M}")
    @SchedulerLock(name = "delivery-no-partner-retry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void retryNoPartner() {
        runOnce();
    }

    /** One sweep; the scheduled method and the tests both call it. Time is the database's clock. */
    public void runOnce() {
        if (!enabled) {
            return;
        }
        long windowSeconds = window.toSeconds();
        long intervalSeconds = interval.toSeconds();

        for (Long id : deliveries.dueForRetry(windowSeconds, intervalSeconds, maxRetries, batch)) {
            try {
                if (claims.claim(id, windowSeconds, intervalSeconds, maxRetries)) {
                    deliveryService.retryNoPartner(id);
                }
            } catch (RuntimeException ex) {
                log.error("Automatic retry of delivery {} failed", id, ex);
            }
        }

        for (Long id : deliveries.dueForOffer(offerAfter.toSeconds(), batch)) {
            try {
                deliveryService.offerOwnDelivery(id);
            } catch (RuntimeException ex) {
                log.error("Could not offer own delivery on delivery {}", id, ex);
            }
        }
    }
}
