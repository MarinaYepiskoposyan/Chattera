package com.chattera.wsgateway.config;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares this ws-gateway pod's own broadcast queue on the shared
 * {@code chattera.events} topic exchange (bean auto-configured by
 * common-messaging), bound with the catch-all {@code room.#} pattern so the
 * pod receives every room/message/status event - see solution-architecture.md
 * CHAT-24 §3 "Chosen topology" and the CHAT-107 implementation-decisions
 * subsection.
 *
 * <p>{@link AnonymousQueue} (non-durable, exclusive, auto-delete, randomly
 * named) is deliberately used instead of a shared named queue: every pod
 * must see <b>every</b> event (broadcast), not compete with other pods for
 * messages, and the queue holds no state worth surviving a restart -
 * Postgres is the source of truth, a missed event is recovered by reconnect
 * + history refetch. This is the opposite of chat-service's
 * {@code chat.receipts} queue (durable, named, competing-consumer), and
 * deliberately so.
 */
@Configuration
public class BroadcastQueueConfig {

    private static final String ROUTING_PATTERN = "room.#";

    @Bean
    public Queue wsGatewayBroadcastQueue() {
        return new AnonymousQueue();
    }

    @Bean
    public Binding wsGatewayBroadcastBinding(Queue wsGatewayBroadcastQueue, TopicExchange chatteraEventsExchange) {
        return BindingBuilder.bind(wsGatewayBroadcastQueue).to(chatteraEventsExchange).with(ROUTING_PATTERN);
    }
}
