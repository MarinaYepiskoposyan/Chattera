package com.chattera.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * REST edge service. Scaffold only for Sprint 1 - see
 * docs/solution-architecture.md for the intended routing/rate-limiting
 * responsibilities. Business logic to follow in a later ticket.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
