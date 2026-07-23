package com.chattera.wsgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WebSocket edge service. Scaffold only for Sprint 1 - see
 * docs/solution-architecture.md for the intended real-time delivery,
 * typing, and presence-key (Redis "presence:{userId}") write
 * responsibilities. Business logic to follow in a later ticket.
 */
@SpringBootApplication
public class WsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WsGatewayApplication.class, args);
    }
}
