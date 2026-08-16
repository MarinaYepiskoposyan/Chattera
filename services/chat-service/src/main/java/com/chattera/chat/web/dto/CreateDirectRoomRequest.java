package com.chattera.chat.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /rooms/direct} request body (CHAT-105) - the target user's
 * Keycloak {@code sub} to find-or-create a DIRECT room with.
 */
public record CreateDirectRoomRequest(@NotBlank String userId) {
}
