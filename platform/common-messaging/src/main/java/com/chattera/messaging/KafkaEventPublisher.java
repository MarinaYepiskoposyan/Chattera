package com.chattera.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import com.chattera.domain.event.DomainEvent;

/**
 * Kafka-backed {@link EventPublisher}. Publishes to a shared topic namespace
 * using the caller-provided topic name, with the namespace prefix defined in
 * {@link ChatteraMessagingProperties}.
 *
 * <p>Per the {@link EventPublisher} contract, {@link #publish} never throws:
 * any Kafka transport failure is logged and swallowed. Postgres remains the
 * source of truth for durable message history.
 */
public class KafkaEventPublisher implements EventPublisher<DomainEvent> {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChatteraMessagingProperties properties;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, ChatteraMessagingProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(String topic, DomainEvent event) {
        String resolvedTopic = properties.resolveTopic(topic);
        try {
            kafkaTemplate.send(resolvedTopic, event);
        } catch (KafkaException publishFailed) {
            log.warn("Failed to publish {} to topic={}: {}",
                    event.getClass().getSimpleName(), resolvedTopic, publishFailed.getMessage(), publishFailed);
        }
    }
}
