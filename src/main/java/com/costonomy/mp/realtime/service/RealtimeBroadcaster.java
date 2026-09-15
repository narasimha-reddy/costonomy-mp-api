package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.domain.RealtimeEvent;

/**
 * Gets an event to every session that should see it, wherever it is connected.
 *
 * <p>A port, because the answer depends on how the application is deployed. On one
 * instance, "everywhere" is this JVM. On several, a session for {@code outlet:12}
 * may be held by an instance that did not produce the event — and the relay runs
 * under a {@code @SchedulerLock}, so it is emphatically <em>not</em> the instance
 * with the sockets. Without a cross-instance hop, realtime would work perfectly in
 * development and deliver nothing to most users in production.
 *
 * <p>Redis carries that hop (see {@code RedisRealtimeBroadcaster}), which is
 * exactly what guardrail 6 permits it for: hints and coordination, never money,
 * orders or credit. The durable copy is already in {@code realtime_event}, so a
 * dropped Redis message costs a client nothing it cannot catch up on by cursor.
 */
public interface RealtimeBroadcaster {

    void broadcast(RealtimeEvent event);
}
