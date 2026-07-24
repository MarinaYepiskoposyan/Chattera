package com.chattera.chat.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.chattera.domain.event.DomainEvent;
import com.chattera.domain.event.RoomMessageCreatedEvent;
import com.chattera.messaging.EventPublisher;

/**
 * Bridges Spring application events published by the service layer onto the
 * RabbitMQ-backed {@link EventPublisher} - only after the originating
 * transaction commits ({@link TransactionPhase#AFTER_COMMIT}), which is what
 * gives us "persist-then-publish" (see solution-architecture.md) without the
 * service layer needing to manage transaction synchronization itself.
 *
 * <p>{@link EventPublisher#publish} never throws (its RabbitMQ
 * implementation logs and swallows transport failures), so a broker outage
 * here cannot turn an already-committed write into a failed request.
 */
@Component
public class ChatEventListener {

    private final EventPublisher<DomainEvent> eventPublisher;

    public ChatEventListener(EventPublisher<DomainEvent> eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomMessageCreated(RoomMessageCreatedEvent event) {
        eventPublisher.publish("room." + event.roomId(), event);
    }
}
