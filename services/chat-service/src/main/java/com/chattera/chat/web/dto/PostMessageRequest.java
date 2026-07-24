package com.chattera.chat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /rooms/{roomId}/messages} request body. {@code 4000} is a
 * pragmatic upper bound (not specified by a ticket/AC) to keep a single
 * message row/event payload bounded - revisit if product requirements need
 * longer messages.
 */
public record PostMessageRequest(@NotBlank @Size(max = 4000) String content) {
}
