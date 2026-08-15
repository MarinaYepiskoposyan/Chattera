package com.chattera.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by ws-gateway (CHAT-107) when a {@link RoomMessageCreatedEvent}
 * it received from the bus was successfully pushed to a locally-connected
 * socket whose user is not the message's sender - i.e. an actual recipient,
 * not the sender's own echo. Consumed by chat-service, which advances the
 * message's status to {@code DELIVERED} (monotonic: SENT -> DELIVERED only).
 * Routing key: {@code receipt.delivered} - see solution-architecture.md
 * "Real-time delivery - CHAT-107 implementation decisions" §2.
 */
public record MessageDeliveredEvent(
        UUID messageId,
        UUID roomId,
        String recipientId,
        Instant deliveredAt,
        Instant occurredAt) implements DomainEvent {
}
