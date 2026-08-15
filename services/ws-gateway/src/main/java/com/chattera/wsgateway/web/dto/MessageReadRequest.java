package com.chattera.wsgateway.web.dto;

import java.util.UUID;

/**
 * Body of a STOMP {@code SEND} to {@code /app/message.read}. {@code roomId}
 * travels in the body (not the destination), so no {@code @MessageMapping}
 * template variable / dot-{@code PathMatcher} config is needed - see
 * solution-architecture.md "What triggers DELIVERED vs READ".
 */
public record MessageReadRequest(UUID roomId, UUID lastReadMessageId) {
}
