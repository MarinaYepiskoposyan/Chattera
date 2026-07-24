package com.chattera.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.chattera.chat.service.exception.InvalidCursorException;

/**
 * Opaque keyset cursor over {@code (created_at, id)} for
 * {@code GET /rooms/{roomId}/messages?before=}. Encodes the exact
 * {@link Instant} read back from the database row (not a truncated/derived
 * value), so decode -> query round-trips without precision loss regardless
 * of the database's timestamp precision.
 */
final class MessageCursor {

    private static final char SEPARATOR = '|';

    private MessageCursor() {
    }

    static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Decoded decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separatorIndex = raw.indexOf(SEPARATOR);
            if (separatorIndex < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            Instant createdAt = Instant.parse(raw.substring(0, separatorIndex));
            UUID id = UUID.fromString(raw.substring(separatorIndex + 1));
            return new Decoded(createdAt, id);
        } catch (RuntimeException malformed) {
            throw new InvalidCursorException("Malformed 'before' cursor");
        }
    }

    record Decoded(Instant createdAt, UUID id) {
    }
}
