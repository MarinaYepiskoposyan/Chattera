package com.chattera.domain.error;

import java.time.Instant;

/**
 * Common error response shape returned by Chattera REST APIs. Shared across
 * services so clients get a consistent error contract regardless of which
 * service produced the failure.
 */
public record ApiError(String code, String message, Instant timestamp) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
