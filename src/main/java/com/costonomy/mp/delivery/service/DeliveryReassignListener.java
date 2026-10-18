package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.DeliveryProviderStoodDownEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Books the next courier when a provider lets go of a consignment before pickup (doc 06 §7).
 *
 * <p>After the commit that recorded the stand-down, and off the calling thread: that thread is the provider's webhook,
 * and booking calls other providers over HTTP. A slow reply would make the provider time out and send the webhook
 * again. A failure here is logged and left for {@link DeliveryAssignmentSweepJob}, which finds any delivery still
 * sitting in {@code DRIVER_CANCELLED} or {@code PICKUP_FAILED}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryReassignListener {

    private final DeliveryService deliveryService;
    private final TaskScheduler taskScheduler;

    @Value("${costonomy.mp.delivery.auto-reassign.enabled:true}")
    private boolean enabled = true;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProviderStoodDown(DeliveryProviderStoodDownEvent event) {
        if (!enabled) {
            return;
        }
        taskScheduler.schedule(() -> run(event), Instant.now());
    }

    void run(DeliveryProviderStoodDownEvent event) {
        try {
            boolean booked = deliveryService.reassignAfterProviderFailure(
                    event.deliveryId(), event.providerDeliveryId(), event.reason());
            log.info("Provider stood down on delivery {}: {}", event.deliveryId(),
                    booked ? "another courier booked" : "no other courier booked");
        } catch (RuntimeException ex) {
            log.error("Could not reassign delivery {} after the provider stood down; the sweep will retry",
                    event.deliveryId(), ex);
        }
    }
}
