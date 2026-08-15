package com.chattera.chat.service;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chattera.chat.repository.MessageRepository;
import com.chattera.domain.event.MessageDeliveredEvent;
import com.chattera.domain.event.MessageReadEvent;
import com.chattera.domain.event.RoomMessageStatusChangedEvent;

/**
 * Applies the delivered/read receipt write-back that {@code ReceiptEventListener}
 * (the {@code receipt.*} RabbitMQ consumer) receives from ws-gateway. See
 * solution-architecture.md "Real-time delivery - CHAT-107 implementation
 * decisions" §2 - status only ever advances (monotonic/idempotent), and a
 * {@link RoomMessageStatusChangedEvent} is published (mirroring
 * {@code MessageService}'s persist-then-publish pattern via
 * {@code ChatEventListener}, AFTER_COMMIT) only if a row was actually
 * advanced, to avoid echoing no-op receipts back to the sender.
 */
@Service
public class MessageStatusService {

    private final MessageRepository messageRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public MessageStatusService(MessageRepository messageRepository, ApplicationEventPublisher applicationEventPublisher) {
        this.messageRepository = messageRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public void applyDelivered(MessageDeliveredEvent event) {
        int updated = messageRepository.markDelivered(event.messageId());
        if (updated > 0) {
            applicationEventPublisher.publishEvent(new RoomMessageStatusChangedEvent(
                    event.messageId(), event.roomId(), "DELIVERED", event.recipientId(), Instant.now()));
        }
    }

    @Transactional
    public void applyRead(MessageReadEvent event) {
        int updated = messageRepository.markReadUpTo(event.roomId(), event.recipientId(), event.messageId());
        if (updated > 0) {
            applicationEventPublisher.publishEvent(new RoomMessageStatusChangedEvent(
                    event.messageId(), event.roomId(), "READ", event.recipientId(), Instant.now()));
        }
    }
}
