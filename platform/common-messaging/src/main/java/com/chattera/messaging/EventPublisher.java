package com.chattera.messaging;

import com.chattera.domain.event.DomainEvent;

/**
 * Publisher-side contract for the Chattera messaging backbone. The concrete
 * transport is RabbitMQ via Spring AMQP (decided CHAT-104, see
 * solution-architecture.md "Event bus decision"; {@code RabbitEventPublisher}
 * in this module is the implementation), but producers (chat-service,
 * file-service) are written against this abstraction rather than the broker
 * API directly so the transport can change later without touching call sites.
 *
 * <p>The bus is transient delivery, not the system of record - PostgreSQL is.
 * Implementations of {@link #publish} must therefore be <b>best-effort and
 * never throw</b>: a transport failure (broker unreachable, etc.) is logged
 * by the implementation and swallowed, never propagated to the caller. This
 * is what lets producers publish after a DB commit without risking turning a
 * successful, already-durable write into a failed REST response.
 *
 * @param <T> the domain event payload type being published
 */
public interface EventPublisher<T extends DomainEvent> {

    /**
     * Publishes an event to the given logical topic/routing key. The mapping
     * from topic name to broker-specific destination (exchange, queue,
     * stream) is an implementation concern. Never throws - see the class
     * Javadoc.
     */
    void publish(String topic, T event);
}
