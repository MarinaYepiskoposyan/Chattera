package com.chattera.wsgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for ws-gateway, bound from {@code chattera.ws-gateway.*}.
 */
@ConfigurationProperties(prefix = "chattera.ws-gateway")
public class WsGatewayProperties {

    /**
     * chat-service's own base URL, used only for the subscribe-time/read
     * membership check ({@code GET /rooms/{roomId}/members/me}) - see
     * solution-architecture.md "Subscribe-time authorization".
     */
    private String chatServiceBaseUrl = "http://localhost:8083";

    public String getChatServiceBaseUrl() {
        return chatServiceBaseUrl;
    }

    public void setChatServiceBaseUrl(String chatServiceBaseUrl) {
        this.chatServiceBaseUrl = chatServiceBaseUrl;
    }
}
