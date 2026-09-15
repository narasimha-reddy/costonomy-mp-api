package com.costonomy.mp.notification.service;

import com.costonomy.mp.notification.domain.NotificationChannel;
import com.costonomy.mp.notification.domain.NotificationDelivery;
import com.costonomy.mp.notification.domain.NotificationDeliveryStatus;
import com.costonomy.mp.notification.provider.NotificationSender;
import com.costonomy.mp.notification.repository.NotificationDeliveryRepository;
import com.costonomy.mp.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gets queued notifications out of the building. Doc 08 §6.
 *
 * <p>{@code CREATED → QUEUED → SENT → DELIVERED}, with bounded exponential backoff
 * on failure. Three things are worth stating about the failure handling, because
 * each is a way this goes wrong quietly.
 *
 * <p><b>A permanent failure is not retried.</b> An unregistered push token belongs
 * to an app that was uninstalled; retrying it every minute for a day produces a
 * queue full of messages for phones that no longer exist, and buries the transient
 * failures that would have succeeded.
 *
 * <p><b>One bad delivery must not stop the sweep.</b> The next one may be the
 * order rejection somebody is waiting for.
 *
 * <p><b>Failing to send does not unsend the notification.</b> It is already in the
 * user's inbox, which is the durable channel; push and SMS are best-effort on top.
 * A dead push token is not a reason to pretend nothing happened.
 */
@Service
@Slf4j
public class NotificationDispatcher {

    private static final int BATCH_SIZE = 100;
    /** Roughly forty minutes of trying, then stop. Beyond that, nobody is reading it. */
    private static final int MAX_ATTEMPTS = 6;

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final Map<NotificationChannel, NotificationSender> senders;

    public NotificationDispatcher(NotificationDeliveryRepository deliveries,
                                  NotificationRepository notifications,
                                  List<NotificationSender> senders) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.senders = senders.stream().collect(Collectors.toMap(
                NotificationSender::channel, Function.identity()));
        log.info("Notification channels available: {}", this.senders.keySet());
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.notifications.dispatch-interval:PT10S}")
    @SchedulerLock(name = "notification-dispatch", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    @Transactional
    public void dispatch() {
        var due = deliveries.findDue(
                List.of(NotificationDeliveryStatus.CREATED, NotificationDeliveryStatus.QUEUED,
                        NotificationDeliveryStatus.FAILED),
                Instant.now(), PageRequest.of(0, BATCH_SIZE));

        for (NotificationDelivery delivery : due) {
            try {
                send(delivery);
            } catch (RuntimeException ex) {
                log.error("Could not dispatch notification delivery {}", delivery.getId(), ex);
            }
        }
    }

    private void send(NotificationDelivery delivery) {
        var sender = senders.get(delivery.getChannel());
        if (sender == null) {
            // A channel with no adapter — a half-finished deploy, or a channel
            // turned off. Failed permanently rather than retried forever against
            // something that does not exist.
            fail(delivery, "No sender for " + delivery.getChannel(), false);
            return;
        }

        var notification = notifications.findById(delivery.getNotificationId()).orElse(null);
        if (notification == null) {
            fail(delivery, "The notification is gone", false);
            return;
        }

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setProvider(sender.providerName());

        try {
            String messageId = sender.send(delivery.getDestination(),
                    notification.getTitle(), notification.getBody());

            delivery.setStatus(NotificationDeliveryStatus.SENT);
            delivery.setProviderMessageId(messageId);
            delivery.setSentAt(Instant.now());
            delivery.setNextAttemptAt(null);
            delivery.setFailureReason(null);
            deliveries.save(delivery);

        } catch (NotificationSender.NotificationSendException ex) {
            fail(delivery, ex.getMessage(), ex.retryable());
        }
    }

    private void fail(NotificationDelivery delivery, String reason, boolean retryable) {
        delivery.setStatus(NotificationDeliveryStatus.FAILED);
        delivery.setFailureReason(reason);

        if (!retryable || delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            delivery.setNextAttemptAt(null);
            log.debug("Notification delivery {} failed permanently: {}",
                    delivery.getId(), reason);
        } else {
            // Exponential, capped. A provider that is down for a minute should not
            // be hit sixty times in that minute.
            long seconds = Math.min(600L, (long) Math.pow(2, delivery.getAttemptCount()));
            delivery.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(seconds)));
        }
        deliveries.save(delivery);
    }

    /**
     * A provider told us a message reached the device.
     *
     * <p>Separate from SENT on purpose: SENT is "the provider accepted it",
     * DELIVERED is "the device acknowledged it", and only some providers report the
     * second. Treating acceptance as delivery would make every dashboard show
     * perfect delivery regardless of what reached a phone.
     */
    @Transactional
    public void markDelivered(Long deliveryId) {
        deliveries.findById(deliveryId).ifPresent(delivery -> {
            delivery.setStatus(NotificationDeliveryStatus.DELIVERED);
            delivery.setDeliveredAt(Instant.now());
            deliveries.save(delivery);
        });
    }
}
