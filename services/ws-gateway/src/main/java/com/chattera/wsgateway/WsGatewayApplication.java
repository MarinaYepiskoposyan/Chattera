package com.chattera.wsgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.chattera.wsgateway.config.WsGatewayProperties;

/**
 * WebSocket edge service (CHAT-107): STOMP-over-WebSocket real-time message
 * delivery, delivered/read receipts, and the {@code presence:{userId}} Redis
 * key writes profile-service already reads. See docs/solution-architecture.md
 * "Real-time delivery - CHAT-107 implementation decisions".
 */
@SpringBootApplication
@EnableConfigurationProperties(WsGatewayProperties.class)
public class WsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WsGatewayApplication.class, args);
    }
}
