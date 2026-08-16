package com.chattera.chat.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.chattera.domain.event.DomainEvent;
import com.chattera.domain.event.RoomMembershipRevokedEvent;
import com.chattera.domain.event.RoomMessageCreatedEvent;
import com.chattera.domain.event.RoomMessageStatusChangedEvent;
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

    /**
     * CHAT-107 receipt write-back, published by {@code MessageStatusService}
     * after it advances a message's status. Same routing-key family as
     * {@link #onRoomMessageCreated} ({@code room.<roomId>}) so it rides the
     * same ws-gateway broadcast queue and reaches the sender's
     * {@code /topic/rooms.{roomId}} subscription.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomMessageStatusChanged(RoomMessageStatusChangedEvent event) {
        eventPublisher.publish("room." + event.roomId(), event);
    }

    /**
     * CHAT-37 membership-revocation write-back, published by
     * {@code RoomService.leaveRoom} after the membership row is deleted. Same
     * routing-key family as {@link #onRoomMessageCreated}
     * ({@code room.<roomId>}) so it rides the same ws-gateway broadcast
     * queue; ws-gateway force-drops the revoked user's live subscription to
     * {@code /topic/rooms.{roomId}} without fanning the event out to anyone
     * else.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomMembershipRevoked(RoomMembershipRevokedEvent event) {
        eventPublisher.publish("room." + event.roomId(), event);
    }
}
