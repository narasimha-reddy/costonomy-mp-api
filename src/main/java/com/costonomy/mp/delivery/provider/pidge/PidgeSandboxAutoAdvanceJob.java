package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test-only (D-196): moves Pidge sandbox deliveries along on their own, so a tester can watch the whole flow without
 * tapping "next step". Does nothing unless the sandbox is on and {@code sandbox-auto-advance} is set, so production,
 * where the sandbox is off, never runs it.
 *
 * <p>How long a delivery has sat in a status is measured here, in memory, from the first sweep that saw it there.
 * {@code updated_at} is no use (the poller refreshes it) and the delivery events' times are the sandbox's invented
 * ones. A restart only restarts the timers.
 */
@Service
@Slf4j
public class PidgeSandboxAutoAdvanceJob {

    /** Deliveries older than this are left alone, so a long-forgotten test booking is not woken up. */
    private static final Duration LOOKBACK = Duration.ofHours(24);

    private static final List<DeliveryStatus> MOVABLE = Arrays.stream(DeliveryStatus.values())
            .filter(s -> PidgeSandboxStages.nextDummyStatus(s).isPresent())
            .toList();

    private record Seen(DeliveryStatus status, Instant since) {
    }

    private final DeliveryRepository deliveries;
    private final PidgeSandboxService sandbox;
    private final PidgeProperties properties;
    private final Clock clock;

    private final Map<Long, Seen> seen = new HashMap<>();

    @Autowired
    public PidgeSandboxAutoAdvanceJob(DeliveryRepository deliveries, PidgeSandboxService sandbox,
                                      PidgeProperties properties) {
        this(deliveries, sandbox, properties, Clock.systemUTC());
    }

    public PidgeSandboxAutoAdvanceJob(DeliveryRepository deliveries, PidgeSandboxService sandbox,
                                      PidgeProperties properties, Clock clock) {
        this.deliveries = deliveries;
        this.sandbox = sandbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5S")
    @SchedulerLock(name = "pidge-sandbox-auto-advance", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
    public void tick() {
        runOnce();
    }

    /** One sweep; the scheduled method and the tests both call it. */
    public synchronized void runOnce() {
        if (!properties.isSandboxAutoAdvanceActive()) {
            seen.clear();
            return;
        }
        Duration interval = properties.getSandboxAutoAdvance();
        Instant now = clock.instant();

        List<Delivery> open = deliveries
                .findByProviderCodeAndModeAndProviderDeliveryIdIsNotNullAndStatusInAndCreatedAtAfter(
                        PidgeDeliveryProvider.CODE, DeliveryMode.COSTONOMY, MOVABLE, now.minus(LOOKBACK));

        Set<Long> openIds = open.stream().map(Delivery::getId).collect(Collectors.toSet());
        seen.keySet().retainAll(openIds);

        for (Delivery delivery : open) {
            Long id = delivery.getId();
            Seen previous = seen.get(id);
            if (previous == null || previous.status() != delivery.getStatus()) {
                seen.put(id, new Seen(delivery.getStatus(), now));
                continue;
            }
            if (Duration.between(previous.since(), now).compareTo(interval) < 0) {
                continue;
            }
            try {
                if (sandbox.advanceAsSystem(id)) {
                    log.info("Pidge sandbox auto-advance: delivery {} left {} after {}s", id,
                            delivery.getStatus(), Duration.between(previous.since(), now).toSeconds());
                }
            } catch (RuntimeException ex) {
                log.error("Pidge sandbox auto-advance of delivery {} failed", id, ex);
            }
            // Whether or not it moved, the next look starts a fresh wait; a move shows as a new status next sweep.
            seen.put(id, new Seen(delivery.getStatus(), now));
        }
    }
}
