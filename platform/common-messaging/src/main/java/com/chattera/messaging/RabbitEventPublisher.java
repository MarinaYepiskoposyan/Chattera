package com.chattera.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.chattera.domain.event.DomainEvent;

/**
 * RabbitMQ-backed {@link EventPublisher}. Publishes to a single shared topic
 * exchange (see {@link ChatteraMessagingProperties}), using the caller's
 * {@code topic} argument as the routing key - e.g. chat-service publishes
 * room messages with a routing key like {@code room.<roomId>} so future
 * consumers (ws-gateway, CHAT-107) can bind queues per room/pattern.
 *
 * <p>Per the {@link EventPublisher} contract, {@link #publish} never throws:
 * any {@link AmqpException} (broker unreachable, channel error, etc.) is
 * logged and swallowed. If no queue is currently bound for the routing key
 * (e.g. CHAT-107's consumer isn't running yet), RabbitMQ simply drops the
 * message - acceptable because Postgres, not the bus, is the source of
 * truth for message history.
 */
public class RabbitEventPublisher implements EventPublisher<DomainEvent> {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate, String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    @Override
    public void publish(String topic, DomainEvent event) {
        try {
            rabbitTemplate.convertAndSend(exchange, topic, event);
        } catch (AmqpException publishFailed) {
            log.warn("Failed to publish {} to exchange={} routingKey={}: {}",
                    event.getClass().getSimpleName(), exchange, topic, publishFailed.getMessage(), publishFailed);
        }
    }
}
