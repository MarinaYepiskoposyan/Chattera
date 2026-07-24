package com.chattera.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the shared RabbitMQ event bus, bound from
 * {@code chattera.messaging.*}. One topic exchange is shared by every
 * producer; individual events are distinguished by the routing key each
 * caller passes to {@link EventPublisher#publish}, not by separate
 * exchanges.
 */
@ConfigurationProperties(prefix = "chattera.messaging")
public class ChatteraMessagingProperties {

    /**
     * Name of the durable topic exchange every Chattera producer publishes
     * to. Consumers (e.g. ws-gateway in CHAT-107) bind their own
     * queues to this exchange with a routing-key pattern.
     */
    private String exchange = "chattera.events";

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }
}
