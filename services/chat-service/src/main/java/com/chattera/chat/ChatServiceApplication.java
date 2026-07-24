package com.chattera.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Room and private messaging service. Room creation/join/leave and message
 * post/history are implemented (CHAT-104): REST + persistence + best-effort
 * publish to the RabbitMQ event bus after commit. Direct messages
 * (CHAT-105) and the WebSocket consumer/real-time delivery side (CHAT-107)
 * are not yet built - see docs/solution-architecture.md.
 */
@SpringBootApplication
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
