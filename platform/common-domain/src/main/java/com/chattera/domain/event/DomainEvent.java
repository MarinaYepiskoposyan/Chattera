package com.chattera.domain.event;

import java.time.Instant;

/**
 * Marker contract for payloads published on the Chattera messaging backbone
 * (see {@code common-messaging}). The transport is intentionally abstracted
 * behind the shared event API so producers and consumers can evolve without
 * coupling to a specific broker implementation.
 */
public interface DomainEvent {

    /**
     * When the event occurred, as recorded by the producing service.
     */
    Instant occurredAt();
}
