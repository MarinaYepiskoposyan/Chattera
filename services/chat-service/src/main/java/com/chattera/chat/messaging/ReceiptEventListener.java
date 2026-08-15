package com.chattera.chat.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.chattera.chat.service.MessageStatusService;
import com.chattera.domain.event.MessageDeliveredEvent;
import com.chattera.domain.event.MessageReadEvent;

/**
 * Consumes {@code receipt.*} events published by ws-gateway (CHAT-107) from
 * {@link ReceiptQueueConfig#QUEUE_NAME}. A single {@code @RabbitListener}
 * class with {@code @RabbitHandler}-annotated overloads (rather than two
 * separate listeners on the same queue, which would compete for each
 * message) lets both event types share one queue - the shared
 * {@code Jackson2JsonMessageConverter} from common-messaging dispatches by
 * the concrete payload type carried in the message's {@code __TypeId__}
 * header.
 */
@Component
@RabbitListener(queues = ReceiptQueueConfig.QUEUE_NAME)
public class ReceiptEventListener {

    private final MessageStatusService messageStatusService;

    public ReceiptEventListener(MessageStatusService messageStatusService) {
        this.messageStatusService = messageStatusService;
    }

    @RabbitHandler
    public void onDelivered(MessageDeliveredEvent event) {
        messageStatusService.applyDelivered(event);
    }

    @RabbitHandler
    public void onRead(MessageReadEvent event) {
        messageStatusService.applyRead(event);
    }
}
