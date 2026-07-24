package com.chattera.chat.web.dto;

import java.util.List;

/**
 * {@code nextCursor} is present only when more (older) history exists;
 * {@code null} means the caller has reached the start of the room's history.
 */
public record MessageHistoryResponse(List<MessageResponse> messages, String nextCursor) {
}
