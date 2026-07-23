package com.chattera.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Room and private messaging service. Scaffold only for Sprint 1 - see
 * docs/solution-architecture.md for the intended room/private-message flows.
 * Business logic to follow in a later ticket (CHAT-104/CHAT-105).
 */
@SpringBootApplication
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
