package com.chattera.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * File upload/storage/metadata/download service. Scaffold only for
 * Sprint 1 - see docs/solution-architecture.md for the intended
 * client -> file service -> object storage -> metadata persistence flow.
 * Business logic to follow in a later ticket (CHAT-106).
 */
@SpringBootApplication
public class FileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}
