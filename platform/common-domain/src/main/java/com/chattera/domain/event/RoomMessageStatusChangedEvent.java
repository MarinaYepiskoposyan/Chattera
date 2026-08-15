package com.chattera.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by chat-service <b>after</b> it persists a {@code DELIVERED}/
 * {@code READ} status change driven by {@link MessageDeliveredEvent}/
 * {@link MessageReadEvent}. Routing key: {@code room.<roomId>} - the same key
 * family {@link RoomMessageCreatedEvent} uses, so it rides the same per-pod
 * ws-gateway broadcast queue and is delivered on
 * {@code /topic/rooms.{roomId}} like any other room event, letting the
 * original sender's client see the status tick update live.
 *
 * <p>{@code status} is kept a plain {@code String} ({@code "DELIVERED"} or
 * {@code "READ"}) rather than chat-service's {@code MessageStatus} enum, so
 * this shared module doesn't depend on a chat-service-internal type.
 */
public record RoomMessageStatusChangedEvent(
        UUID messageId,
        UUID roomId,
        String status,
        String recipientId,
        Instant occurredAt) implements DomainEvent {
}
