package com.chattera.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by chat-service (CHAT-104) after a room/DM message row commits.
 * Transient delivery only - PostgreSQL is the source of truth, so a consumer
 * that never sees this event (no subscriber bound, broker unreachable, etc.)
 * does not lose data; it re-fetches history via chat-service's REST API.
 * Consumed by ws-gateway (CHAT-107, not yet built) to fan the message out to
 * live sockets. Lives in common-domain, not chat-service, so it is a shared
 * on-the-wire contract any future consumer can depend on directly.
 */
public record RoomMessageCreatedEvent(
        UUID messageId,
        UUID roomId,
        String senderId,
        String content,
        Instant createdAt,
        Instant occurredAt) implements DomainEvent {
}
