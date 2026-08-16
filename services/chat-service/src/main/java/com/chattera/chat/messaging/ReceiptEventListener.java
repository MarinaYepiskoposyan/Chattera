package com.chattera.chat.messaging;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.chattera.chat.service.MessageStatusService;
import com.chattera.domain.event.MessageDeliveredEvent;
import com.chattera.domain.event.MessageReadEvent;

/**
 * Consumes receipt events published by ws-gateway (CHAT-107) from the
 * shared Chattera event stream. A single listener class with typed handlers
 * lets both event types share the same topic pattern while keeping message
 * processing explicit and room-scoped.
 */
@Component
@KafkaListener(topicPattern = "chattera.events.*")
public class ReceiptEventListener {

    private final MessageStatusService messageStatusService;

    public ReceiptEventListener(MessageStatusService messageStatusService) {
        this.messageStatusService = messageStatusService;
    }

    @KafkaHandler
    public void onDelivered(MessageDeliveredEvent event) {
        messageStatusService.applyDelivered(event);
    }

    @KafkaHandler
    public void onRead(MessageReadEvent event) {
        messageStatusService.applyRead(event);
    }

    /**
     * The shared chattera.events.* wildcard subscription also assigns this listener every
     * other event type on the namespace (room message/status/membership events, published
     * by chat-service itself). Without a default handler Spring throws for any payload type
     * with no matching @KafkaHandler; this is a deliberate no-op, not a bug.
     */
    @KafkaHandler(isDefault = true)
    public void onOtherEvent(Object event) {
    }
}
