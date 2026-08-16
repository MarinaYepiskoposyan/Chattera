package com.chattera.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the shared Kafka event bus, bound from
 * {@code chattera.messaging.*}. All Chattera producers publish into a shared
 * topic namespace; callers supply the logical topic name to
 * {@link EventPublisher#publish}, and the prefix is added automatically.
 */
@ConfigurationProperties(prefix = "chattera.messaging")
public class ChatteraMessagingProperties {

    /**
     * Shared topic namespace prefix for all Chattera events.
     */
    private String topicPrefix = "chattera.events";

    public String getTopicPrefix() {
        return topicPrefix;
    }

    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }

    public String resolveTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return topicPrefix;
        }
        return topic.startsWith(topicPrefix) ? topic : topicPrefix + "." + topic;
    }
}
