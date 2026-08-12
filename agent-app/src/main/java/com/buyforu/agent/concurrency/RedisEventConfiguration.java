package com.buyforu.agent.concurrency;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 跨实例运行事件通知订阅；权威事件内容仍从 PostgreSQL 回放。 */
@Configuration
public class RedisEventConfiguration {
    @Bean
    RedisMessageListenerContainer runEventListener(RedisConnectionFactory connectionFactory,
                                                   RunEventNotifier notifier) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(notifier, new ChannelTopic(RunEventNotifier.CHANNEL));
        return container;
    }
}
