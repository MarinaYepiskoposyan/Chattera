package com.chattera.messaging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

import com.chattera.domain.event.DomainEvent;

/**
 * Auto-configures the shared Kafka event bus for any Chattera service that has
 * spring-kafka on the classpath and standard {@code spring.kafka.*}
 * connection properties set. Mirrors the common-security pattern: a service
 * gets a working {@code EventPublisher<DomainEvent>} bean for free just by
 * adding the dependency, and can still fully override any bean here.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(ChatteraMessagingProperties.class)
public class KafkaEventPublisherAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher<DomainEvent> eventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            ChatteraMessagingProperties properties) {
        return new KafkaEventPublisher(kafkaTemplate, properties);
    }
}
