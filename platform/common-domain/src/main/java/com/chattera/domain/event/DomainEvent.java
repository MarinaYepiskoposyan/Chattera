package com.chattera.domain.event;

import java.time.Instant;

/**
 * Marker contract for payloads published on the Chattera messaging backbone
 * (see {@code common-messaging}). The event bus technology (RabbitMQ, Kafka,
 * or Redis Streams) is not yet finalized - this interface exists so future
 * event payloads (room message, private message, delivery/read receipts,
 * file-shared, etc.) have a common shape without committing to a transport.
 */
public interface DomainEvent {

    /**
     * When the event occurred, as recorded by the producing service.
     */
    Instant occurredAt();
}
