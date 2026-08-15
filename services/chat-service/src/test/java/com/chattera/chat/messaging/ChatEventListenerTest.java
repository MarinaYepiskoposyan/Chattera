package com.chattera.chat.messaging;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.chattera.domain.event.DomainEvent;
import com.chattera.domain.event.RoomMessageCreatedEvent;
import com.chattera.domain.event.RoomMessageStatusChangedEvent;
import com.chattera.messaging.EventPublisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatEventListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void routesTheEventToTheEventPublisherWithARoomScopedRoutingKey() {
        EventPublisher<DomainEvent> eventPublisher = mock(EventPublisher.class);
        ChatEventListener listener = new ChatEventListener(eventPublisher);
        UUID roomId = UUID.randomUUID();
        RoomMessageCreatedEvent event = new RoomMessageCreatedEvent(
                UUID.randomUUID(), roomId, "sender", "hi", Instant.now(), Instant.now());

        listener.onRoomMessageCreated(event);

        verify(eventPublisher).publish("room." + roomId, event);
    }

    @Test
    @SuppressWarnings("unchecked")
    void routesAStatusChangedEventToTheEventPublisherWithARoomScopedRoutingKey() {
        EventPublisher<DomainEvent> eventPublisher = mock(EventPublisher.class);
        ChatEventListener listener = new ChatEventListener(eventPublisher);
        UUID roomId = UUID.randomUUID();
        RoomMessageStatusChangedEvent event = new RoomMessageStatusChangedEvent(
                UUID.randomUUID(), roomId, "DELIVERED", "recipient", Instant.now());

        listener.onRoomMessageStatusChanged(event);

        verify(eventPublisher).publish("room." + roomId, event);
    }
}
