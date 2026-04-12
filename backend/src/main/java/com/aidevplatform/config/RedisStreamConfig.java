package com.aidevplatform.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import jakarta.annotation.PostConstruct;

/**
 * Redis Streams configuration for agent signal communication.
 *
 * This configuration sets up Redis Streams as a persistent message broker between
 * the Agent Service and Backend Service, ensuring reliable signal delivery
 * even when services restart or crash.
 *
 * Architecture:
 * - Producer: Agent Service or Backend sends signals to 'agent:signals' stream
 * - Consumer: AgentSignalConsumer polls and processes signals with acknowledgment
 * - Persistence: Messages remain in stream until consumed and acknowledged
 */
@Configuration
@Slf4j
public class RedisStreamConfig {

    @Bean
    public RedisTemplate<String, String> redisStreamTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisMessageListenerContainer redisStreamMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    /**
     * Initialize Redis Stream consumer group on startup.
     * Creates the 'agent-consumers' consumer group if it doesn't exist.
     *
     * Uses direct Redis connection to execute XGROUP CREATE command.
     *
     * Command: XGROUP CREATE stream groupName ID MKSTREAM
     * - groupName: agent-consumers
     * - ID: $ means start consuming from newest messages
     * - MKSTREAM creates the stream if it doesn't exist
     */
    @Bean
    public RedisStreamInitiator redisStreamInitiator(RedisConnectionFactory factory) {
        return new RedisStreamInitiator(factory, "agent:signals", "agent-consumers");
    }

    /**
     * Helper class to initialize Redis Stream consumer group.
     */
    static class RedisStreamInitiator {

        private final RedisConnectionFactory factory;
        private final String streamName;
        private final String groupName;

        RedisStreamInitiator(RedisConnectionFactory factory, String streamName, String groupName) {
            this.factory = factory;
            this.streamName = streamName;
            this.groupName = groupName;
            init();
        }

        public void init() {
            try (var connection = factory.getConnection()) {
                connection.xGroupCreate(
                    streamName.getBytes(),
                    groupName,
                    ReadOffset.lastConsumed(),
                    true  // MKSTREAM
                );
                log.info("Redis Stream consumer group '{}' initialized for stream '{}'", groupName, streamName);
            } catch (Exception e) {
                log.info("Consumer group '{}' for stream '{}' already exists: {}",
                        groupName, streamName, e.getMessage());
            }
        }
    }
}
