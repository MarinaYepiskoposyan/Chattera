package com.chattera.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by ws-gateway (CHAT-107) when a client sends a STOMP {@code SEND}
 * to {@code /app/message.read}, signalling it has rendered messages up to
 * (and including) {@code messageId} in its open conversation view.
 * {@code messageId} is the recipient's <b>last-read</b> message - chat-service
 * (the consumer) applies "read up to" semantics: every message in the room
 * from a different sender, not already {@code READ}, with
 * {@code createdAt <= that message's createdAt} is marked {@code READ}.
 * Routing key: {@code receipt.read} - see solution-architecture.md "Real-time
 * delivery - CHAT-107 implementation decisions" §2.
 */
public record MessageReadEvent(
        UUID messageId,
        UUID roomId,
        String recipientId,
        Instant readAt,
        Instant occurredAt) implements DomainEvent {
}
