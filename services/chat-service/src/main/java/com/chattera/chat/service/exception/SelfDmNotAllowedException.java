package com.chattera.chat.service.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when {@code POST /rooms/direct} is asked to find-or-create a DM
 * between a user and themselves (CHAT-105 AC-4).
 */
public class SelfDmNotAllowedException extends ChatDomainException {

    public SelfDmNotAllowedException() {
        super(HttpStatus.BAD_REQUEST, "SELF_DM_NOT_ALLOWED", "Cannot start a direct message with yourself");
    }
}
