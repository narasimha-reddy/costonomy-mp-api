package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.domain.RealtimeEvent;
import com.costonomy.mp.realtime.repository.RealtimeEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Fans out across instances, over Redis pub/sub.
 *
 * <p>Needed the moment there is more than one instance, and not before. The relay
 * that produces events runs under a {@code @SchedulerLock} — one instance drains
 * the outbox — while sockets are spread across all of them. Without this hop, the
 * instance holding the lock would deliver to its own sessions and silently to
 * nobody else's.
 *
 * <p><b>Only the event id crosses.</b> Each instance re-reads the row from
 * {@code realtime_event} before delivering, so Redis never carries tenant data and
 * a message lost in transit costs nothing — the durable copy is in MySQL, and the
 * client's cursor will find it. That keeps Redis inside guardrail 6: coordination,
 * never the record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "costonomy.mp.realtime.broadcaster", havingValue = "REDIS")
public class RedisRealtimeBroadcaster implements RealtimeBroadcaster, MessageListener {

    public static final String TOPIC = "costonomy.mp.realtime";

    private final StringRedisTemplate redis;
    private final RealtimeSessionRegistry sessions;
    private final RealtimeEventRepository events;

    @Override
    public void broadcast(RealtimeEvent event) {
        // Delivered locally first, so this instance's clients are not waiting on a
        // round trip through Redis to see their own action take effect.
        sessions.deliver(event);
        try {
            redis.convertAndSend(TOPIC, String.valueOf(event.getId()));
        } catch (RuntimeException ex) {
            // Redis being down degrades realtime to polling, which is the designed
            // fallback (doc 06 §9) — it is not a reason to fail anything.
            log.warn("Could not publish realtime event {} to Redis: {}",
                    event.getId(), ex.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8).replace("\"", "");
        try {
            events.findById(Long.parseLong(body)).ifPresent(sessions::deliver);
        } catch (NumberFormatException ex) {
            log.debug("Ignoring malformed realtime message: {}", body);
        }
    }
}
