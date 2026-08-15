package com.chattera.chat.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares chat-service's durable {@code receipt.*} consumer queue on the
 * shared {@code chattera.events} topic exchange (the {@link TopicExchange}
 * bean is auto-configured by common-messaging's
 * {@code RabbitEventPublisherAutoConfiguration}). Unlike ws-gateway's
 * per-pod broadcast queue (CHAT-107, transient/exclusive - every pod must
 * see every room event), this is a single named, durable, competing-consumer
 * queue: chat-service instances share it, and each receipt is processed
 * exactly once across the fleet, which is fine because receipt processing is
 * idempotent (see {@code MessageStatusService}) regardless of which instance
 * handles it. See solution-architecture.md "Real-time delivery - CHAT-107
 * implementation decisions" §2.
 */
@Configuration
public class ReceiptQueueConfig {

    public static final String QUEUE_NAME = "chat.receipts";
    private static final String ROUTING_PATTERN = "receipt.*";

    @Bean
    public Queue chatReceiptsQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding chatReceiptsBinding(Queue chatReceiptsQueue, TopicExchange chatteraEventsExchange) {
        return BindingBuilder.bind(chatReceiptsQueue).to(chatteraEventsExchange).with(ROUTING_PATTERN);
    }
}
