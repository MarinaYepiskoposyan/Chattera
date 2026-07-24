package com.chattera.chat.service.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the {@code before} keyset cursor on
 * {@code GET /rooms/{roomId}/messages} can't be decoded.
 */
public class InvalidCursorException extends ChatDomainException {

    public InvalidCursorException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", message);
    }
}
