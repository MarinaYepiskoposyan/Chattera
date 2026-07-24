package com.chattera.chat.service.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for chat-service's domain-level failures, each carrying the
 * HTTP status/error code it maps to (see {@code GlobalExceptionHandler}).
 */
public abstract class ChatDomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ChatDomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
