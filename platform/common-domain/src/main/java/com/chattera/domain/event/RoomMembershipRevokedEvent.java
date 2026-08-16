package com.chattera.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by chat-service (CHAT-37) after a user's membership in a room
 * ends (self-leave now; owner-removal when that endpoint is added). Consumed
 * by ws-gateway, which force-drops that user's live subscription to
 * {@code /topic/rooms.{roomId}}.
 * Routing key: {@code room.<roomId>} - same family as
 * {@link RoomMessageCreatedEvent}, so it rides the existing per-pod broadcast
 * queue ({@code room.#} binding); every pod receives it, and only the pod(s)
 * holding that user's socket act on it. See solution-architecture.md
 * "Membership revocation of a live subscription - RoomMembershipRevokedEvent
 * (CHAT-37)".
 */
public record RoomMembershipRevokedEvent(
        UUID roomId,
        String userId,
        Instant occurredAt) implements DomainEvent {
}
