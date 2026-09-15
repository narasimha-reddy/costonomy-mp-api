package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.repository.RealtimeEventRepository;
import com.costonomy.mp.realtime.repository.RealtimeTicketStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Housekeeping.
 *
 * <p>Both tables are write-heavy and read-recent: a client only ever asks for
 * events after its cursor, and a ticket is useless thirty seconds after it is
 * issued. Left alone they would grow without bound to serve queries nobody makes.
 *
 * <p>The retention window is deliberately longer than any plausible offline
 * period. A client that comes back after it has elapsed gets nothing from its
 * cursor — which is <b>correct and safe</b>, because doc 05 §16 has it refreshing
 * authoritative state on cold start regardless. Realtime is a prompt to refresh;
 * missing the prompt costs nothing but latency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeJobs {

    private final RealtimeEventRepository events;
    private final RealtimeTicketStore tickets;

    @Value("${costonomy.mp.realtime.event-retention:7d}")
    private Duration eventRetention;

    @Scheduled(fixedDelayString = "${costonomy.mp.realtime.cleanup-interval:PT1H}")
    @SchedulerLock(name = "realtime-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    @Transactional
    public void cleanUp() {
        Instant now = Instant.now();

        long spentTickets = tickets.purgeExpired(now);
        long oldEvents = events.deleteByCreatedAtBefore(now.minus(eventRetention));

        if (spentTickets > 0 || oldEvents > 0) {
            log.info("Realtime cleanup removed {} tickets and {} events",
                    spentTickets, oldEvents);
        }
    }
}
