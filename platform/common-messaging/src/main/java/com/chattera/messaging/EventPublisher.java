package com.chattera.messaging;

import com.chattera.domain.event.DomainEvent;

/**
 * Publisher-side contract for the Chattera messaging backbone. The concrete
 * transport (RabbitMQ, Kafka, or Redis Streams - see solution-architecture.md)
 * is not yet chosen; this interface lets producers (chat-service, file-service)
 * be written against a stable abstraction and be wired to a real
 * implementation once that decision is made.
 *
 * @param <T> the domain event payload type being published
 */
public interface EventPublisher<T extends DomainEvent> {

    /**
     * Publishes an event to the given logical topic/routing key. The mapping
     * from topic name to broker-specific destination (exchange, queue,
     * stream) is an implementation concern.
     */
    void publish(String topic, T event);
}
