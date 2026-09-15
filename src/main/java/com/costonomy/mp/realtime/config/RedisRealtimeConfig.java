package com.costonomy.mp.realtime.config;

import com.costonomy.mp.realtime.service.RedisRealtimeBroadcaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this instance to the cross-instance realtime topic.
 *
 * <p>Only when {@code costonomy.mp.realtime.broadcaster=REDIS}. Left off, nothing
 * here is created and no Redis connection is opened — which is what lets local
 * development and CI run with no Redis at all, as doc 10 §6 requires of every
 * external dependency.
 */
@Configuration
@ConditionalOnProperty(name = "costonomy.mp.realtime.broadcaster", havingValue = "REDIS")
public class RedisRealtimeConfig {

    @Bean
    public RedisMessageListenerContainer realtimeListenerContainer(
            RedisConnectionFactory connectionFactory, RedisRealtimeBroadcaster broadcaster) {

        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster, new ChannelTopic(RedisRealtimeBroadcaster.TOPIC));
        return container;
    }
}
